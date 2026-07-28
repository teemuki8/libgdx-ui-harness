plugins {
    application
}

dependencies {
    api(project(":harness-protocol"))
    implementation(libs.jackson.databind)
    api(libs.mcp)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("dev.gdx.uiharness.mcp.Main")
}
