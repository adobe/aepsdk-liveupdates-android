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

# Used by the aepsdk-commons "android-validate-code" reusable workflow (build-and-test.yml).
lint: checkformat checkstyle

# Used by the aepsdk-commons "android-javadoc" reusable workflow (build-and-test.yml).
# enableDokkaDoc = true in build.gradle.kts wires up this task; output defaults to
# code/liveupdates/build/dokka/javadoc, matched by javadoc-build-path in the workflow.
javadoc:
	(./code/gradlew -p code/$(EXTENSION-LIBRARY-FOLDER-NAME) dokkaJavadoc)
