package com.cmdruid.pubsub.integration

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test Runner for PubSub Android App
 * 
 * This class provides organized test execution for different test categories.
 * Run with: ./gradlew testDebugUnitTest
 * 
 * Test Categories:
 * 1. SubscriptionIntegrationTestSuite - Complete subscription testing
 * 2. Individual test classes can be run separately for focused testing
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    SubscriptionIntegrationTestSuite::class
)
class TestRunner {
    // This class serves as the main test runner
    // All test suites are defined in the @Suite.SuiteClasses annotation
}

/**
 * How to run specific test categories:
 * 
 * 1. Run all subscription tests:
 *    ./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.integration.SubscriptionIntegrationTestSuite"
 * 
 * 2. Run Android integration tests only:
 *    ./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.integration.AndroidSubscriptionTest"
 * 
 * 3. Run service integration tests only:
 *    ./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.integration.ServiceIntegrationTest"
 * 
 * 4. Run automated subscription tests only:
 *    ./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.automation.*"
 * 
 * 5. Run health monitoring tests only:
 *    ./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.service.health.*"
 * 
 * 6. Run all tests:
 *    ./gradlew testDebugUnitTest
 */
