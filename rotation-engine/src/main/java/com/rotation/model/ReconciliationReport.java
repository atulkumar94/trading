package com.rotation.model;

import java.util.ArrayList;
import java.util.List;

/** Hard reconciliation checks plus non-fatal data-quality warnings for one run. */
public final class ReconciliationReport {

    /** One named check. */
    public static final class Check {
        public final String name;
        public final boolean passed;
        public final String detail;

        public Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }
    }

    private final List<Check> checks;
    private final List<String> warnings;

    public ReconciliationReport(List<Check> checks, List<String> warnings) {
        this.checks = List.copyOf(checks);
        this.warnings = List.copyOf(warnings);
    }

    public List<Check> checks() {
        return checks;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean passed() {
        return checks.stream().allMatch(c -> c.passed);
    }

    /** Fail loudly: a failed reconciliation means the reports disagree with the engine. */
    public void requirePassed() {
        List<String> failures = new ArrayList<>();
        for (Check check : checks) {
            if (!check.passed) {
                failures.add(check.name + ": " + check.detail);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Daily report reconciliation failed:\n  - "
                    + String.join("\n  - ", failures));
        }
    }
}
