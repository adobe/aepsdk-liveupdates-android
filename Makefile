EXTENSION-LIBRARY-FOLDER-NAME = liveupdates
TEST-APP-FOLDER-NAME = testapp

# Build the SDK (phone variant).
assemble-phone:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) assemblePhone)

# Build the sample app.
assemble-app:
	(./code/gradlew -p code/$(TEST-APP-FOLDER-NAME) assemble)

# Run unit tests.
unit-test:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) testPhoneDebugUnitTest)

# Run unit tests and produce the JaCoCo coverage report (report.xml is what CI uploads
# to Codecov). This is AGP's task, which correctly measures this 100%-Kotlin module.
unit-test-coverage:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) createPhoneDebugUnitTestCoverageReport)

# Optional quality gates (Spotless + Checkstyle are enabled in the module's build.gradle.kts).
format:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) spotlessApply)

checkformat:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) spotlessCheck)

checkstyle:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) checkstyle)

# Remove build outputs (also clears the JReleaser staging-deploy dir before a snapshot publish).
clean:
	(./code/gradlew -p code clean)

# Generate Javadoc (Dokka) — consumed by the CI Javadoc job.
javadoc:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) dokkaJavadoc)

# Build the release variant of the SDK (prerequisite for publishing).
assemble-phone-release:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) assemblePhoneRelease)

# Publish the release build to the local Maven cache for JitPack consumption.
ci-publish-maven-local-jitpack: assemble-phone-release
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) publishReleasePublicationToMavenLocal -Pjitpack)

# Stage a snapshot into the JReleaser deploy directory (deployed to the Central Portal by CI).
ci-publish-staging: clean
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) publish)

# Stage a release into the JReleaser deploy directory (deployed to the Central Portal by CI).
ci-publish: assemble-phone-release
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) publish -Prelease)
