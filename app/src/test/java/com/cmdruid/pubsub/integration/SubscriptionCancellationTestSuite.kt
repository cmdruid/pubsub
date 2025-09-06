package com.cmdruid.pubsub.integration

import com.cmdruid.pubsub.service.RelayConnectionManagerCancellationTest
import com.cmdruid.pubsub.service.SubscriptionCancellationIntegrationTest
import com.cmdruid.pubsub.service.SubscriptionCancellationTrackerTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Comprehensive test suite for subscription cancellation functionality
 * 
 * This suite covers:
 * - Unit tests for SubscriptionCancellationTracker
 * - Integration tests for message processing with cancellation
 * - Tests for RelayConnectionManager cancellation functionality
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    SubscriptionCancellationTrackerTest::class,
    SubscriptionCancellationIntegrationTest::class,
    RelayConnectionManagerCancellationTest::class
)
class SubscriptionCancellationTestSuite
