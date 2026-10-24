package ru.gora.musicbattle.client;

/** Tick-to-render interpolation shared by active battle camera movement and FOV. */
public final class BattleCameraInterpolation {
    private double previousX;
    private double previousY;
    private double previousZ;
    private double currentX;
    private double currentY;
    private double currentZ;
    private float previousYaw;
    private float previousPitch;
    private float previousFov;
    private float currentYaw;
    private float currentPitch;
    private float currentFov;
    private boolean valid;

    public void snap(double x, double y, double z, float yaw, float pitch, float fov) {
        currentX = previousX = finite(x);
        currentY = previousY = finite(y);
        currentZ = previousZ = finite(z);
        currentYaw = previousYaw = wrapDegrees(finite(yaw));
        currentPitch = previousPitch = finite(pitch);
        currentFov = previousFov = finite(fov);
        valid = true;
    }

    public void captureTickStart() {
        if (!valid) return;
        previousX = currentX;
        previousY = currentY;
        previousZ = currentZ;
        previousYaw = currentYaw;
        previousPitch = currentPitch;
        previousFov = currentFov;
    }

    public void approach(double x, double y, double z, float yaw, float pitch, float fov,
                         double positionAmount, float rotationAmount, float fovAmount) {
        if (!valid) {
            snap(x, y, z, yaw, pitch, fov);
            return;
        }
        double position = clamp(positionAmount);
        float rotation = (float) clamp(rotationAmount);
        float fovStep = (float) clamp(fovAmount);
        currentX = lerp(position, currentX, finite(x));
        currentY = lerp(position, currentY, finite(y));
        currentZ = lerp(position, currentZ, finite(z));
        currentYaw = rotLerp(rotation, currentYaw, finite(yaw));
        currentPitch = lerp(rotation, currentPitch, finite(pitch));
        currentFov = lerp(fovStep, currentFov, finite(fov));
    }

    public Sample sample(float partialTick) {
        float partial = (float) clamp(partialTick);
        if (!valid) return new Sample(0, 0, 0, 0, 0, 0);
        return new Sample(
                lerp(partial, previousX, currentX),
                lerp(partial, previousY, currentY),
                lerp(partial, previousZ, currentZ),
                rotLerp(partial, previousYaw, currentYaw),
                lerp(partial, previousPitch, currentPitch),
                lerp(partial, previousFov, currentFov));
    }

    public boolean valid() {
        return valid;
    }

    public void clear() {
        valid = false;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double lerp(double amount, double from, double to) {
        return from + amount * (to - from);
    }

    private static float lerp(float amount, float from, float to) {
        return from + amount * (to - from);
    }

    private static float rotLerp(float amount, float from, float to) {
        return wrapDegrees(from + amount * wrapDegrees(to - from));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    public record Sample(double x, double y, double z, float yaw, float pitch, float fov) {
    }
}
