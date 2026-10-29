# Jobbdialer - ProGuard/R8 rules.
#
# Minification is disabled for the release build (isMinifyEnabled = false in
# app/build.gradle.kts), so this file is currently unused. It exists so that minification
# can be switched on later without touching the build script.
#
# The app uses only framework classes; activities and the JobService are referenced from the
# manifest and are kept automatically by AAPT-generated rules.
