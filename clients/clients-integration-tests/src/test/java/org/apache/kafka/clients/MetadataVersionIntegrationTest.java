/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients;

import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.FeatureUpdate;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.test.ClusterInstance;
import org.apache.kafka.common.test.api.ClusterConfigProperty;
import org.apache.kafka.common.test.api.ClusterTest;
import org.apache.kafka.common.test.api.ClusterTests;
import org.apache.kafka.common.test.api.Type;
import org.apache.kafka.server.common.MetadataVersion;
import org.apache.kafka.test.TestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetadataVersionIntegrationTest {
    @ClusterTests(value = {
        @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_3_IV3),
        @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_4_IV0),
        @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_5_IV0),
        @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_6_IV0),
        @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_6_IV1)
    })
    public void testBasicMetadataVersionUpgrade(ClusterInstance clusterInstance) throws Exception {
        try (var admin = clusterInstance.admin()) {
            var describeResult = admin.describeFeatures();
            var ff = describeResult.featureMetadata().get().finalizedFeatures().get(MetadataVersion.FEATURE_NAME);
            assertEquals(clusterInstance.config().metadataVersion().featureLevel(), ff.minVersionLevel());
            assertEquals(clusterInstance.config().metadataVersion().featureLevel(), ff.maxVersionLevel());

            // Update to new version
            short updateVersion = MetadataVersion.IBP_3_7_IV1.featureLevel();
            var updateResult = admin.updateFeatures(
                    Map.of("metadata.version", new FeatureUpdate(updateVersion, FeatureUpdate.UpgradeType.UPGRADE)));
            updateResult.all().get();

            // Verify that new version is visible on broker
            TestUtils.waitForCondition(() -> {
                try {
                    var describeResult2 = admin.describeFeatures();
                    var ff2 = describeResult2.featureMetadata().get().finalizedFeatures().get(MetadataVersion.FEATURE_NAME);
                    return ff2.minVersionLevel() == updateVersion && ff2.maxVersionLevel() == updateVersion;
                } catch (Exception e) {
                    return false;
                }
            }, "Never saw metadata.version increase on broker");
        }
    }

    @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_3_9_IV0)
    public void testUpgradeSameVersion(ClusterInstance clusterInstance) throws Exception {
        try (var admin = clusterInstance.admin()) {
            short updateVersion = MetadataVersion.IBP_3_9_IV0.featureLevel();
            var updateResult = admin.updateFeatures(
                    Map.of("metadata.version", new FeatureUpdate(updateVersion, FeatureUpdate.UpgradeType.UPGRADE)));
            updateResult.all().get();
        }
    }

    @ClusterTest(types = Type.KRAFT, metadataVersion = MetadataVersion.IBP_4_3_IV1,
            serverProperties = @ClusterConfigProperty(key = "log.segment.bytes", value = "1048576")
    )
    public void testMetadataVersionUpgradeWithValidTopicConfigs(ClusterInstance clusterInstance) throws Exception {
        try (var admin = clusterInstance.admin()) {
            // Create a topic with a valid dynamic config
            admin.createTopics(List.of(new NewTopic("test-topic", 1, (short) 1))).all().get();
            admin.incrementalAlterConfigs(Map.of(
                new ConfigResource(ConfigResource.Type.BROKER, "0"),
                List.of(new AlterConfigOp(
                    new ConfigEntry("log.segment.bytes", String.valueOf(2 * 1024 * 1024)),
                    AlterConfigOp.OpType.SET))
            )).all().get();

            // Upgrade metadata.version past IBP_4_0_IV0 — should succeed
            // because the config passes pre-flight validation
            short targetVersion = MetadataVersion.IBP_4_3_IV2.featureLevel();
            admin.updateFeatures(Map.of(
                MetadataVersion.FEATURE_NAME,
                new FeatureUpdate(targetVersion, FeatureUpdate.UpgradeType.UPGRADE)
            )).all().get();

            TestUtils.waitForCondition(() -> {
                try {
                    var ff = admin.describeFeatures().featureMetadata().get()
                        .finalizedFeatures().get(MetadataVersion.FEATURE_NAME);
                    return ff.maxVersionLevel() >= targetVersion;
                } catch (Exception e) {
                    return false;
                }
            }, "metadata.version did not reach " + targetVersion);
        }
    }

    @ClusterTest(types = Type.KRAFT)
    public void testDefaultIsLatestVersion(ClusterInstance clusterInstance) throws Exception {
        try (var admin = clusterInstance.admin()) {
            var describeResult = admin.describeFeatures();
            var ff = describeResult.featureMetadata().get().finalizedFeatures().get(MetadataVersion.FEATURE_NAME);
            assertEquals(MetadataVersion.latestTesting().featureLevel(), ff.minVersionLevel(),
                    "If this test fails, check the default MetadataVersion in the @ClusterTest annotation");
            assertEquals(MetadataVersion.latestTesting().featureLevel(), ff.maxVersionLevel());
        }
    }
}
