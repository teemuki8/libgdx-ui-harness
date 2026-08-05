package dev.gdx.uiharness.core.locator;

public record TestIdLocator(String testId) implements Locator {
    public TestIdLocator {
        TextMatch.requireBounded(testId, "testId");
    }
}
