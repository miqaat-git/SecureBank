package com.securebank.transaction.workflow;

/**
 * Result of attempting one workflow step.
 *
 * @param outcome  what happened
 * @param code     safe result code (ours, or a sanitized remote code)
 * @param attempts remote send attempts recorded so far for this step
 */
public record StepResult(Outcome outcome, String code, int attempts) {

    public enum Outcome {
        /** Remote side definitively applied the operation. */
        SUCCEEDED,
        /** Remote side definitively refused; the operation was not applied. */
        REJECTED,
        /** Inconclusive; try again later with the SAME operation id. */
        RETRY_LATER,
        /** Attempts exhausted and an ambiguous outcome occurred: money may have moved. */
        EXHAUSTED_AMBIGUOUS,
        /** Attempts exhausted but every failure was provably "not applied". */
        EXHAUSTED_NOT_APPLIED
    }
}
