package com.kartaguez.pocoma.domain.pot.policy.scope;

import java.util.Objects;

public class Scope {

    private final Resource resourceValue;
    private final SubResource subResourceValue;
    private final Action actionValue;

    public Scope(Resource resourceValue, SubResource subResourceValue, Action actionValue) {
        this.resourceValue = resourceValue;
        this.subResourceValue = subResourceValue;
        this.actionValue = actionValue;
    }

    public static final Scope of(String scopeString) {
        String[] parts = scopeString.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid scope format: " + scopeString);
        }
        String resourcePart = parts[0];
        String actionPart = parts[1];
        String[] resourceParts = resourcePart.split("\\.");
        Resource resource = Resource.valueOf(resourceParts[0].toUpperCase());
        SubResource subResource = resourceParts.length > 1 ? SubResource.valueOf(resourceParts[1].toUpperCase()) : null;
        Action action = Action.valueOf(actionPart.toUpperCase());
        return new Scope(resource, subResource, action);
    }

    public String toString() {
        return (resourceValue == null ? "" : resourceValue.name().toLowerCase()) + ((subResourceValue == null || "".equals(subResourceValue.name())) ? "" : "." + subResourceValue.name().toLowerCase()) + ":" + (actionValue == null ? "" : actionValue.name().toLowerCase());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Scope scope)) {
            return false;
        }
        return resourceValue == scope.resourceValue
                && subResourceValue == scope.subResourceValue
                && actionValue == scope.actionValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceValue, subResourceValue, actionValue);
    }

    public enum Resource {

        POT("pot"),
        SHAREHOLDER("shareholder"),
        EXPENSE("expense");

        private final String label;

        private Resource(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }

    }

    public enum SubResource {
        DETAILS("details"),
        WEIGHT("weight"),
        SHARES("shares");

        private final String label;

        private SubResource(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }

    }

    public enum Action {
        CREATE("create"),
        READ("read"),
        UPDATE("update"),
        DELETE("delete");

        private final String label;

        private Action(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }
    }

}
