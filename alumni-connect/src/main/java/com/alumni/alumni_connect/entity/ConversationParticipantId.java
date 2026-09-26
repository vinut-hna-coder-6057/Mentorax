package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import java.io.Serializable;
import java.util.Objects;

public class ConversationParticipantId implements Serializable {
    private Long conversation;
    private Long user;

    public ConversationParticipantId() { }

    public ConversationParticipantId(Long conversation, Long user) {
        this.conversation = conversation;
        this.user = user;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConversationParticipantId that)) return false;
        return Objects.equals(conversation, that.conversation)
                && Objects.equals(user, that.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversation, user);
    }
}
