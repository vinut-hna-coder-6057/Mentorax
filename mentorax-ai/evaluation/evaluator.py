"""Reusable, deterministic metrics and validation for the synthetic corpus."""
from collections import defaultdict
import math

INTENTS = {"CAREER_MENTOR_SEARCH", "ALUMNI_QUESTION", "GENERAL_KNOWLEDGE",
           "GREETING_OR_SMALLTALK", "UNSUPPORTED_OR_UNCLEAR", "VALIDATION_ERROR"}
REQUIRED_CATEGORIES = {"career_core", "paraphrase", "misspelling", "multi_skill",
                       "alumni_question", "weak_match", "misleading_context", "no_match",
                       "general_question", "unsupported", "greeting", "ambiguous", "validation"}


def materialize_question(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and isinstance(value.get("repeat"), str) and isinstance(value.get("count"), int):
        return value["repeat"] * value["count"]
    raise ValueError("question must be a string or repeat/count specification")


def validate_dataset(dataset):
    errors = []
    if dataset.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    profiles = dataset.get("profiles")
    questions = dataset.get("questions")
    if not isinstance(profiles, list) or not profiles:
        errors.append("profiles must be a nonempty list")
        profiles = []
    if not isinstance(questions, list) or not questions:
        errors.append("questions must be a nonempty list")
        questions = []
    ids = [str(item.get("id")) for item in profiles if isinstance(item, dict)]
    if len(ids) != len(set(ids)):
        errors.append("profile IDs must be unique")
    profile_ids = set(ids)
    for profile in profiles:
        if not isinstance(profile, dict) or not all(key in profile for key in ("id", "key", "jobTitle", "company", "skills", "bio", "interests")):
            errors.append("each profile must contain id, key, jobTitle, company, skills, bio, interests")
        elif not isinstance(profile["skills"], list) or not all(isinstance(skill, str) for skill in profile["skills"]):
            errors.append(f"profile {profile['id']} skills must be a string list")
    case_ids = []
    for case in questions:
        if not isinstance(case, dict) or not all(key in case for key in ("id", "category", "question", "expectedIntent", "expectedRecommendations", "relevance", "rationale")):
            errors.append("each question must contain the complete labeled case schema")
            continue
        case_ids.append(case["id"])
        if case["expectedIntent"] not in INTENTS:
            errors.append(f"{case['id']} has unknown expectedIntent")
        try:
            materialize_question(case["question"])
        except ValueError as error:
            errors.append(f"{case['id']}: {error}")
        labels = case["relevance"]
        if not isinstance(labels, dict) or any(str(profile_id) not in profile_ids for profile_id in labels):
            errors.append(f"{case['id']} relevance must reference known profiles")
            labels = {}
        if any(label not in (0, 1, 2) or isinstance(label, bool) for label in labels.values()):
            errors.append(f"{case['id']} labels must be integers 0, 1, or 2")
        positives = {str(key) for key, label in labels.items() if label > 0}
        if not isinstance(case["rationale"], dict) or set(case["rationale"]) != positives:
            errors.append(f"{case['id']} needs one rationale per positive label and none for negatives")
        if case["expectedRecommendations"] != (case["expectedIntent"] in {"CAREER_MENTOR_SEARCH", "ALUMNI_QUESTION"} and bool(positives)):
            errors.append(f"{case['id']} recommendation expectation conflicts with intent/labels")
    if len(case_ids) != len(set(case_ids)):
        errors.append("question IDs must be unique")
    if {case.get("category") for case in questions if isinstance(case, dict)} != REQUIRED_CATEGORIES:
        errors.append("question categories do not match the documented category set")
    if errors:
        raise ValueError("Invalid evaluation dataset: " + "; ".join(errors))
    return True


def validate_ranking_result(result, candidate_ids):
    """Validate the rank endpoint contract before consuming IDs for metrics."""
    expected_ids = list(candidate_ids)
    if len(expected_ids) != len(set(expected_ids)):
        raise ValueError("evaluation candidates contain duplicate IDs")
    if not isinstance(result, dict) or not isinstance(result.get("rankedIds"), list) or not isinstance(result.get("scores"), dict):
        raise ValueError("ranking result must contain rankedIds and scores")
    ranked_ids = result["rankedIds"]
    if any(not isinstance(item, int) or isinstance(item, bool) for item in ranked_ids):
        raise ValueError("rankedIds must contain integer IDs")
    if len(ranked_ids) != len(set(ranked_ids)) or set(ranked_ids) != set(expected_ids):
        raise ValueError("rankedIds must contain each candidate ID exactly once")
    scores = result["scores"]
    if set(scores) != {str(item) for item in expected_ids}:
        raise ValueError("score keys must exactly match candidate IDs")
    for score in scores.values():
        if not isinstance(score, (int, float)) or isinstance(score, bool) or not math.isfinite(score) or not 0 <= score <= 1:
            raise ValueError("scores must be finite numbers in [0, 1]")
    return True


def ranking_metrics(ranked_ids, relevance, cutoffs=(3, 5, 10)):
    """Binary relevance is label > 0; NDCG uses graded gain 2**label - 1."""
    ranked = list(ranked_ids)
    if len(ranked) != len(set(ranked)):
        raise ValueError("ranking contains duplicate candidate IDs")
    if any(not isinstance(score, int) or isinstance(score, bool) or score not in (0, 1, 2)
           for score in relevance.values()):
        raise ValueError("relevance labels must be integer grades 0, 1, or 2")
    positive = {str(key) for key, score in relevance.items() if score > 0}
    result = {"recommendedCount": len(ranked), "relevantCount": len(positive), "irrelevantRecommendations": sum(str(item) not in positive for item in ranked)}
    for k in cutoffs:
        top = ranked[:k]
        hits = sum(str(item) in positive for item in top)
        result[f"precisionAt{k}"] = hits / len(top) if top else None
        result[f"recallAt{k}"] = hits / len(positive) if positive else None
        result[f"hitRateAt{k}"] = float(hits > 0)
        first_relevant = next((index + 1 for index, item in enumerate(ranked) if str(item) in positive), None)
        result[f"mrrAt{k}"] = 1 / first_relevant if first_relevant is not None and first_relevant <= k else (0.0 if positive else None)
        dcg = sum((2 ** int(relevance.get(str(item), 0)) - 1) / math.log2(index + 2) for index, item in enumerate(top))
        ideal = sorted((int(score) for score in relevance.values()), reverse=True)[:k]
        idcg = sum((2 ** score - 1) / math.log2(index + 2) for index, score in enumerate(ideal))
        result[f"ndcgAt{k}"] = dcg / idcg if idcg else None
    result["reciprocalRank"] = next((1 / (index + 1) for index, item in enumerate(ranked) if str(item) in positive), 0.0) if positive else None
    return result


def summarize(cases, ranked_by_id):
    groups = defaultdict(list)
    for case in cases:
        groups[case["category"]].append(case)
    return {"caseCount": len(cases), "categories": {
        category: _aggregate(items, ranked_by_id) for category, items in sorted(groups.items())
    }, "overall": _aggregate(cases, ranked_by_id)}


def _aggregate(cases, ranked_by_id):
    rankings = [ranking_metrics(ranked_by_id.get(case["id"], []), case["relevance"]) for case in cases]
    values = {}
    for key in rankings[0] if rankings else ():
        present = [row[key] for row in rankings if row[key] is not None]
        values[key] = {"value": sum(present) / len(present) if present else None,
                       "numerator": round(sum(present), 6) if present else 0,
                       "denominator": len(present), "excludedUndefined": len(rankings) - len(present)}
    recommendations = sum(row["recommendedCount"] for row in rankings)
    irrelevant = sum(row["irrelevantRecommendations"] for row in rankings)
    values["irrelevantRecommendationRate"] = {"value": irrelevant / recommendations if recommendations else None,
                                               "numerator": irrelevant, "denominator": recommendations}
    return values
