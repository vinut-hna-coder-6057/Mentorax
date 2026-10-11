import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});
  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  final _name = TextEditingController();
  final _bio = TextEditingController();
  final _location = TextEditingController();
  final _college = TextEditingController();
  final _branch = TextEditingController();
  final _passoutYear = TextEditingController();
  final _rollno = TextEditingController();
  final _section = TextEditingController();
  final _skills = TextEditingController();
  final _company = TextEditingController();
  final _jobRole = TextEditingController();
  final _linkedin = TextEditingController();
  final _github = TextEditingController();
  final _interests = TextEditingController();
  bool _loaded = false;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _bio.dispose();
    _location.dispose();
    for (final controller in [
      _college,
      _branch,
      _passoutYear,
      _rollno,
      _section,
      _skills,
      _company,
      _jobRole,
      _linkedin,
      _github,
      _interests
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  void _populateFields(User user) {
    _name.text = user.name;
    _bio.text = user.bio ?? '';
    _location.text = user.location ?? '';
    _college.text = user.college ?? '';
    _branch.text = user.branch ?? '';
    _passoutYear.text = user.passoutYear ?? '';
    _rollno.text = user.rollno ?? '';
    _section.text = user.section ?? '';
    _skills.text = user.skills ?? '';
    _company.text = user.company ?? '';
    _jobRole.text = user.jobRole ?? '';
    _linkedin.text = user.linkedin ?? '';
    _github.text = user.github ?? '';
    _interests.text = user.interests ?? '';
  }

  bool _hasChanges(User user) =>
      _name.text.trim() != user.name.trim() ||
      _bio.text.trim() != (user.bio ?? '').trim() ||
      _location.text.trim() != (user.location ?? '').trim() ||
      _college.text.trim() != (user.college ?? '').trim() ||
      _branch.text.trim() != (user.branch ?? '').trim() ||
      _passoutYear.text.trim() != (user.passoutYear ?? '').trim() ||
      _rollno.text.trim() != (user.rollno ?? '').trim() ||
      _section.text.trim() != (user.section ?? '').trim() ||
      _skills.text.trim() != (user.skills ?? '').trim() ||
      _company.text.trim() != (user.company ?? '').trim() ||
      _jobRole.text.trim() != (user.jobRole ?? '').trim() ||
      _linkedin.text.trim() != (user.linkedin ?? '').trim() ||
      _github.text.trim() != (user.github ?? '').trim() ||
      _interests.text.trim() != (user.interests ?? '').trim();

  @override
  Widget build(BuildContext context) {
    final u = ref.watch(authProvider).valueOrNull;
    final themeMode = ref.watch(themeModeProvider);
    if (u == null) {
      return const EmptyState(
        title: 'Profile unavailable',
        message: 'Sign in again to view and edit your profile.',
        icon: Icons.person_outline,
      );
    }
    if (!_loaded) {
      _loaded = true;
      _populateFields(u);
    }
    final hasChanges = _hasChanges(u);
    final compactAppearance = MediaQuery.sizeOf(context).width < 440;
    final themeDropdown = SizedBox(
      width: compactAppearance ? double.infinity : 155,
      child: DropdownButtonFormField<ThemeMode>(
        initialValue: themeMode,
        decoration: const InputDecoration(
          labelText: 'Theme',
          contentPadding: EdgeInsets.symmetric(
            horizontal: AppSpacing.md,
            vertical: AppSpacing.xs,
          ),
        ),
        items: const [
          DropdownMenuItem(value: ThemeMode.system, child: Text('System')),
          DropdownMenuItem(value: ThemeMode.light, child: Text('Light')),
          DropdownMenuItem(value: ThemeMode.dark, child: Text('Dark')),
        ],
        onChanged: (value) async {
          if (value == null) return;
          try {
            await ref.read(themeModeProvider.notifier).setThemeMode(value);
          } catch (error) {
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(
                    'Theme changed, but could not save preference: ${userFacingError(error)}',
                  ),
                ),
              );
            }
          }
        },
      ),
    );
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 780),
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            AppCard(
              child: Row(
                children: [
                  UserAvatar(
                      name: u.name, imageUrl: u.profileImage, radius: 34),
                  const SizedBox(width: AppSpacing.lg),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          u.name,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        Text(u.email),
                        const SizedBox(height: AppSpacing.sm),
                        StatusBadge(u.role.name),
                        if (u.status.isNotEmpty) ...[
                          const SizedBox(height: AppSpacing.xs),
                          StatusBadge(u.status),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            AppCard(
              child: compactAppearance
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Row(
                          children: [
                            Icon(Icons.palette_outlined),
                            SizedBox(width: AppSpacing.md),
                            Text('Appearance'),
                          ],
                        ),
                        const SizedBox(height: AppSpacing.sm),
                        themeDropdown,
                      ],
                    )
                  : Row(
                      children: [
                        const Icon(Icons.palette_outlined),
                        const SizedBox(width: AppSpacing.md),
                        const Expanded(child: Text('Appearance')),
                        themeDropdown,
                      ],
                    ),
            ),
            const SizedBox(height: AppSpacing.xl),
            const AppSectionTitle('Edit your profile'),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  _profileField(
                    _name,
                    'Name',
                    icon: Icons.person_outline,
                  ),
                  _profileField(
                    _bio,
                    'About you',
                    icon: Icons.notes_outlined,
                    maxLines: 3,
                  ),
                  _profileField(
                    _location,
                    'Location',
                    icon: Icons.location_on_outlined,
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Education'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_college, 'College'),
                  _profileField(_branch, 'Branch'),
                  _profileField(
                    _passoutYear,
                    'Passout year',
                    keyboardType: TextInputType.number,
                  ),
                  _profileField(_rollno, 'Roll number'),
                  _profileField(_section, 'Section'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Experience'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_jobRole, 'Job role'),
                  _profileField(_company, 'Company'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Skills and interests'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_skills, 'Skills'),
                  _profileField(_interests, 'Interests'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Links'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_linkedin, 'LinkedIn'),
                  _profileField(_github, 'GitHub'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              onPressed: _saving || !hasChanges
                  ? null
                  : () async {
                      if (_name.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Name cannot be empty.'),
                          ),
                        );
                        return;
                      }
                      if (_passoutYear.text.trim().isNotEmpty &&
                          !RegExp(r'^\d{4}$')
                              .hasMatch(_passoutYear.text.trim())) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content:
                                Text('Enter a valid 4-digit passout year.'),
                          ),
                        );
                        return;
                      }
                      setState(() => _saving = true);
                      final updated = User(
                        id: u.id,
                        name: _name.text.trim(),
                        email: u.email,
                        role: u.role,
                        status: u.status,
                        bio: _bio.text.trim(),
                        location: _location.text.trim(),
                        college: _college.text.trim(),
                        branch: _branch.text.trim(),
                        passoutYear: _passoutYear.text.trim(),
                        rollno: _rollno.text.trim(),
                        section: _section.text.trim(),
                        skills: _skills.text.trim(),
                        company: _company.text.trim(),
                        jobRole: _jobRole.text.trim(),
                        linkedin: _linkedin.text.trim(),
                        profileImage: u.profileImage,
                        interests: _interests.text.trim(),
                        github: _github.text.trim(),
                      );
                      try {
                        final saved = await ref
                            .read(appRepositoryProvider)
                            .updateUser(updated);
                        ref.read(authProvider.notifier).setUser(saved);
                        if (!context.mounted) return;
                        setState(() => _saving = false);
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Profile saved')),
                        );
                      } catch (error) {
                        if (!context.mounted) return;
                        setState(() => _saving = false);
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(userFacingError(error))),
                        );
                      }
                    },
              child: Text(_saving ? 'Saving…' : 'Save profile'),
            ),
            if (hasChanges) ...[
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton(
                onPressed:
                    _saving ? null : () => setState(() => _populateFields(u)),
                child: const Text('Discard changes'),
              ),
            ],
            const SizedBox(height: AppSpacing.sm),
            OutlinedButton(
              onPressed: () => ref.read(authProvider.notifier).signOut(),
              child: const Text('Sign out'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _profileField(
    TextEditingController controller,
    String label, {
    IconData? icon,
    int maxLines = 1,
    TextInputType? keyboardType,
  }) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.md),
        child: TextField(
          controller: controller,
          onChanged: (_) => setState(() {}),
          maxLines: maxLines,
          keyboardType: keyboardType,
          decoration: InputDecoration(
            labelText: label,
            prefixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      );
}
