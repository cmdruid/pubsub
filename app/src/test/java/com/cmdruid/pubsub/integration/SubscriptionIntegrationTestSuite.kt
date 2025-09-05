package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.automation.AutomatedSubscriptionTest
import com.cmdruid.pubsub.automation.EndToEndSubscriptionTest
import com.cmdruid.pubsub.service.SubscriptionReliabilityValidationTest
import com.cmdruid.pubsub.service.health.*
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Comprehensive Android Test Suite for Subscription Functionality
 * 
 * This test suite runs all subscription-related tests as part of the Android test framework.
 * Run with: ./gradlew testDebugUnitTest
 * 
 * Test Categories:
 * - Core subscription reliability validation
 * - Automated subscription testing with TestWebSocketServer
 * - End-to-end subscription scenarios
 * - Health monitoring and connection management
 * - Cross-subscription isolation and bug prevention
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    // Core subscription reliability tests
    SubscriptionReliabilityValidationTest::class,
    
    // Automated subscription testing framework
    AutomatedSubscriptionTest::class,
    EndToEndSubscriptionTest::class,
    
    // Simple Android integration test
    SimpleAndroidTest::class,
    
    // Health monitoring architecture tests
    HealthEvaluatorTest::class,
    HealthCheckOrchestratorTest::class,
    HealthMonitorV2Test::class,
    
    // User scenario validation
    UserScenarioValidationTest::class,
    
    // Duplicate detection and metrics
    com.cmdruid.pubsub.service.DuplicateDetectionMetricsTest::class
)
class SubscriptionIntegrationTestSuite {
    // This class serves as a test suite container
    // All tests are defined in the @Suite.SuiteClasses annotation
}
