package ru.aw.assistant.ui;

public final class AnimatedValue {
    private float value;
    private long previous = System.nanoTime();
    public AnimatedValue(float initial) { value = initial; }
    public float update(float target, boolean enabled) {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - previous) / 1_000_000_000.0);
        previous = now;
        value = enabled ? (float) (value + (target - value) * (1 - Math.exp(-18 * seconds))) : target;
        if (Math.abs(target - value) < 0.001f) value = target;
        return value;
    }
    public void snap(float next) { value = next; previous = System.nanoTime(); }
    public float value() { return value; }
}
