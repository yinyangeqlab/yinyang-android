plugins {
    id("com.android.application") version "9.0.0"
}
val licenseAssets = tasks.register<Sync>("bundlePhoneEqLicenses") {
    from(rootProject.file("LICENSE"), rootProject.file("LICENSE-APACHE-2.0.txt"), rootProject.file("NOTICE.md"))
    into(layout.buildDirectory.dir("generated/phone-eq-licenses/licenses"))
}
val reportClient = tasks.register<Sync>("bundleReportClient") {
    from(rootProject.file("../android/src/ReportClient.java"))
    into(layout.buildDirectory.dir("generated/report-client/com/yinyangeq/qudelixprobe"))
}
android {
    namespace = "com.superglobalpeq.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yinyangeq.android.playback"
        minSdk = 33
        targetSdk = 36
        versionCode = 14
        versionName = "0.3.7"
    }
    buildFeatures {
        aidl = true
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/phone-eq-licenses").get().asFile)
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/report-client").get().asFile)
}
tasks.named("preBuild").configure { dependsOn(licenseAssets, reportClient) }
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
