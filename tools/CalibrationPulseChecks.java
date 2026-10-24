import java.util.List;

public final class CalibrationPulseChecks {
    public static void main(String[] args) throws Exception {
        Class<?> pulse;
        try {
            pulse = Class.forName("ru.gora.musicbattle.data.CalibrationPulse");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Calibration pulse is not implemented", missing);
        }
        var error = pulse.getMethod("tapErrorMs", double.class);
        var sample = pulse.getMethod("sample", long.class, int.class);
        var median = pulse.getMethod("median", List.class);
        var burst = pulse.getMethod("hitBurst", double.class);
        near((double) burst.invoke(null, 1199.0), 0);
        near((double) burst.invoke(null, 1200.0), 1);
        near((double) burst.invoke(null, 1400.0), 0.5);
        near((double) burst.invoke(null, 1600.0), 0);
        near((double) burst.invoke(null, 2699.0), 0);
        near((double) burst.invoke(null, 2700.0), 1);
        near((double) error.invoke(null, 1200.0), 0);
        near((double) error.invoke(null, 2780.0), 80);
        near((double) error.invoke(null, 4150.0), -50);
        near((double) median.invoke(null, List.of(20.0, 40.0, 500.0, 30.0, 10.0)), 30);
        near((double) median.invoke(null, List.of(10.0, 20.0)), 15);
        for (int offset : new int[] {-500, -100, 0, 100, 500}) {
            long first = Math.round((1200.0 + offset) * 44.1);
            for (long frame = 0; frame < first; frame += 97) {
                if ((short) sample.invoke(null, frame, offset) != 0) throw new AssertionError("Early click");
            }
            boolean audible = false;
            for (long frame = first; frame < first + 800; frame++) {
                short value = (short) sample.invoke(null, frame, offset);
                audible |= value != 0;
                if (Math.abs((int) value) > 12000) throw new AssertionError("Unsafe click amplitude");
                if (value != (short) sample.invoke(null, frame + 66150, offset)) throw new AssertionError("Period drift");
            }
            if (!audible) throw new AssertionError("Silent click");
            if ((short) sample.invoke(null, first + 1200, offset) != 0) throw new AssertionError("Click too long");
        }
        System.out.println("Calibration pulse: timing, offset, bounded PCM and tap median PASS");
    }
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.0001) throw new AssertionError(actual + " != " + expected);
    }
}
