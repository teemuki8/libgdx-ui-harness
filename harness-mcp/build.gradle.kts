plugins {
    application
}

dependencies {
    implementation(project(":harness-protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.mcp)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("dev.gdx.uiharness.mcp.Main")
}
