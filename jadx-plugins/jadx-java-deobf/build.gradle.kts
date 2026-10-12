plugins {
	id("jadx-library")
}

dependencies {
	api(project(":jadx-core"))
	implementation("org.ow2.asm:asm:9.7.1")
	testImplementation(project(":jadx-plugins:jadx-java-input"))
}
