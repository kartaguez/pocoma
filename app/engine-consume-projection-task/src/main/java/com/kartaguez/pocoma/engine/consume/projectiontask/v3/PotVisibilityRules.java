package com.kartaguez.pocoma.engine.consume.projectiontask.v3;
import java.util.*;
/** Business rule owned solely by MaterializePotVisibility's source. */
public final class PotVisibilityRules {
    public record Attachment(boolean deleted,UUID userId) {}
    private PotVisibilityRules() {}
    public static Set<UUID> visible(UUID creator,boolean deleted,Collection<Attachment> attachments) {
        Objects.requireNonNull(creator); if(deleted) return Set.of();
        var users=new HashSet<UUID>(); users.add(creator);
        for(var a:attachments) if(!a.deleted() && a.userId()!=null) users.add(a.userId());
        return Set.copyOf(users);
    }
}
