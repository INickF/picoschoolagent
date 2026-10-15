package com.picoschool.agent;

/** A request the agent refuses; the message is a stable error code sent to the PC. */
final class AgentException extends Exception {
    AgentException(String code) {
        super(code);
    }
}
