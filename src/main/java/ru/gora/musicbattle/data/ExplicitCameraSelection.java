package ru.gora.musicbattle.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Server-session ordering of explicit selections; inspecting/editing cameras never writes it. */
public final class ExplicitCameraSelection<O, C> {
    private final Map<O, C> choices = new LinkedHashMap<>();

    public void select(O owner, C camera) {
        if (owner == null || camera == null) return;
        choices.remove(owner);
        choices.put(owner, camera);
    }

    public C latest(BiPredicate<O, C> eligible) {
        C result = null;
        for (Map.Entry<O, C> choice : choices.entrySet()) {
            if (eligible.test(choice.getKey(), choice.getValue())) result = choice.getValue();
        }
        return result;
    }

    /** Coverage belongs to the control booth; its linked camera can be anywhere loaded. */
    public C latest(Predicate<O> ownerCovered, BiPredicate<O, C> cameraAvailable) {
        return latest((owner, camera) -> ownerCovered.test(owner) && cameraAvailable.test(owner, camera));
    }

    public void remove(O owner) { choices.remove(owner); }
}
