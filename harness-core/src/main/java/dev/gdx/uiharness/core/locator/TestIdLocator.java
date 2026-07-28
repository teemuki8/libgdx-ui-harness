package dev.gdx.uiharness.core.locator;

record TestIdLocator(String testId) implements Locator {
    TestIdLocator {
        TextMatch.requireBounded(testId, "testId");
    }
}
