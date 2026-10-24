package ru.gora.musicbattle.data;

import net.minecraft.world.phys.Vec3;

/** Pure collision-response checks; world raycasts are exercised by the compiled client/server integration. */
public final class CameraCollisionChecks {
    public static void main(String[] args) {
        Vec3 from = new Vec3(0.5D, 1.5D, 0.5D);
        Vec3 desired = new Vec3(2.5D, 1.5D, 0.5D);
        Vec3 stopped = CameraCollision.stopBeforeHit(from, desired, new Vec3(1.0D, 1.5D, 0.5D));
        require(stopped.x < 1.0D && stopped.x > from.x, "Camera crossed or moved away from wall");
        require(Math.abs(stopped.y - 1.5D) < 0.000001D && Math.abs(stopped.z - 0.5D) < 0.000001D,
                "Collision response changed an unrelated axis");
        Vec3 tiny = CameraCollision.stopBeforeHit(from, desired, from.add(0.01D, 0.0D, 0.0D));
        require(tiny.equals(from), "Near-contact collision must not push the camera backwards");
        require(CameraCollision.stopBeforeHit(from, from, from).equals(from), "Zero movement must stay finite");
        System.out.println("Camera collision regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
