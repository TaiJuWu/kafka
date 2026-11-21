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
package org.apache.kafka.tools;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ClusterMirrorDescription;
import org.apache.kafka.clients.admin.ClusterMirrorListing;
import org.apache.kafka.clients.admin.DescribeClusterMirrorsOptions;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.common.utils.internals.Exit;
import org.apache.kafka.server.util.CommandDefaultOptions;
import org.apache.kafka.server.util.CommandLineUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSpecBuilder;

/**
 * kafka-cluster-mirrors.sh: creates cluster mirrors and manages mirror topics (KIP-1279).
 */
public class ClusterMirrorsCommand {

    public static void main(String... args) {
        Exit.exit(mainNoExit(args));
    }

    static int mainNoExit(String... args) {
        try {
            ClusterMirrorsCommandOptions opts = new ClusterMirrorsCommandOptions(args);
            Properties config = opts.has(opts.commandConfigOpt)
                ? Utils.loadProps(opts.valueOf(opts.commandConfigOpt)) : new Properties();
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, opts.valueOf(opts.bootstrapServerOpt));
            try (Admin admin = Admin.create(config)) {
                execute(admin, opts);
            }
            return 0;
        } catch (ExecutionException e) {
            System.err.println("Error: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
            return 1;
        } catch (Throwable e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static void execute(Admin admin, ClusterMirrorsCommandOptions opts) throws Exception {
        String mirror = opts.valueOf(opts.mirrorOpt);
        List<String> topics = opts.topics();
        if (opts.has(opts.createOpt)) {
            Properties props = Utils.loadProps(opts.valueOf(opts.mirrorConfigOpt));
            Map<String, String> configs = new HashMap<>();
            props.forEach((k, v) -> configs.put(k.toString(), v.toString()));
            admin.createClusterMirror(mirror, configs).all().get();
            System.out.println("Created mirror " + mirror);
        } else if (opts.has(opts.startOpt)) {
            admin.startMirrorTopics(mirror, topics).all().get();
            System.out.println("Started mirroring " + String.join(",", topics) + " from " + mirror);
        } else if (opts.has(opts.stopOpt)) {
            admin.stopMirrorTopics(mirror, topics).all().get();
            System.out.println("Stopped mirroring " + String.join(",", topics) + " from " + mirror);
        } else if (opts.has(opts.pauseOpt)) {
            admin.pauseMirrorTopics(mirror, topics).all().get();
            System.out.println("Paused mirroring " + String.join(",", topics) + " from " + mirror);
        } else if (opts.has(opts.resumeOpt)) {
            admin.resumeMirrorTopics(mirror, topics).all().get();
            System.out.println("Resumed mirroring " + String.join(",", topics) + " from " + mirror);
        } else if (opts.has(opts.recoverOpt)) {
            admin.recoverMirrorTopics(mirror, topics).all().get();
            System.out.println("Recovered mirroring " + String.join(",", topics) + " from " + mirror);
        } else if (opts.has(opts.deleteOpt)) {
            admin.deleteClusterMirror(mirror).all().get();
            System.out.println("Deleted mirror " + mirror);
        } else if (opts.has(opts.listOpt)) {
            Collection<ClusterMirrorListing> listings = admin.listClusterMirrors().all().get();
            System.out.printf("%-20s %-36s %-30s %s%n", "MIRROR", "SOURCE-CLUSTER-ID", "SOURCE-BOOTSTRAP", "TOPICS");
            listings.stream().sorted(Comparator.comparing(ClusterMirrorListing::mirrorName)).forEach(listing ->
                System.out.printf("%-20s %-36s %-30s %d%n", listing.mirrorName(), listing.sourceClusterId(),
                    listing.sourceBootstrap(), listing.topicNames().map(List::size).orElse(0)));
        } else if (opts.has(opts.describeOpt)) {
            describe(admin, opts, mirror);
        }
    }

    private static void describe(Admin admin, ClusterMirrorsCommandOptions opts, String mirror) throws Exception {
        Map<String, ClusterMirrorDescription> descriptions = admin.describeClusterMirrors(
            mirror == null ? List.of() : List.of(mirror), Map.of(),
            new DescribeClusterMirrorsOptions().includeMirrorState(true).includeMirrorOffset(true)).allDescriptions().get();
        boolean failedOnly = opts.has(opts.failedOpt);
        boolean json = opts.has(opts.jsonOpt);
        for (ClusterMirrorDescription description : descriptions.values()) {
            List<ClusterMirrorDescription.LeaderStateDescription> states = description.leaderStates().values().stream()
                .flatMap(Collection::stream)
                .filter(state -> !failedOnly || "FAILED".equals(state.state()))
                .sorted(Comparator.comparing((ClusterMirrorDescription.LeaderStateDescription s) -> s.topicPartition().topic())
                    .thenComparingInt(s -> s.topicPartition().partition()))
                .collect(Collectors.toList());
            if (json) {
                System.out.println("{\"mirror\":\"" + description.mirrorName() + "\",\"sourceClusterId\":\"" +
                    description.sourceClusterId() + "\",\"partitions\":[" + states.stream().map(s ->
                        "{\"topic\":\"" + s.topicPartition().topic() + "\",\"partition\":" + s.topicPartition().partition() +
                        ",\"state\":\"" + s.state() + "\",\"sourceOffset\":" + s.sourceOffset() + ",\"destinationOffset\":" +
                        s.destinationOffset() + ",\"lag\":" + s.lag() + ",\"retryAttempt\":" + s.retryAttempt() + "}")
                    .collect(Collectors.joining(",")) + "]}");
            } else {
                System.out.println("Mirror: " + description.mirrorName() + "  SourceClusterId: " + description.sourceClusterId() +
                    "  SourceBootstrap: " + description.sourceBootstrap());
                System.out.printf("%-30s %-10s %-15s %-15s %-15s %-10s %s%n", "TOPIC", "PARTITION", "STATE",
                    "SOURCE-OFFSET", "DEST-OFFSET", "LAG", failedOnly ? "ERROR" : "LAST-MIRROR-EPOCH");
                for (ClusterMirrorDescription.LeaderStateDescription s : states) {
                    System.out.printf("%-30s %-10d %-15s %-15d %-15d %-10d %s%n", s.topicPartition().topic(),
                        s.topicPartition().partition(), s.state(), s.sourceOffset(), s.destinationOffset(), s.lag(),
                        failedOnly ? s.errorMessage() : String.valueOf(s.lastMirrorEpoch()));
                }
            }
        }
    }

    static final class ClusterMirrorsCommandOptions extends CommandDefaultOptions {
        final ArgumentAcceptingOptionSpec<String> bootstrapServerOpt;
        final ArgumentAcceptingOptionSpec<String> commandConfigOpt;
        final ArgumentAcceptingOptionSpec<String> mirrorOpt;
        final ArgumentAcceptingOptionSpec<String> mirrorConfigOpt;
        final ArgumentAcceptingOptionSpec<String> topicsOpt;
        final OptionSpecBuilder createOpt;
        final OptionSpecBuilder deleteOpt;
        final OptionSpecBuilder describeOpt;
        final OptionSpecBuilder listOpt;
        final OptionSpecBuilder startOpt;
        final OptionSpecBuilder stopOpt;
        final OptionSpecBuilder pauseOpt;
        final OptionSpecBuilder resumeOpt;
        final OptionSpecBuilder recoverOpt;
        final OptionSpecBuilder failedOpt;
        final OptionSpecBuilder jsonOpt;

        ClusterMirrorsCommandOptions(String[] args) {
            super(args);
            bootstrapServerOpt = parser.accepts("bootstrap-server", "REQUIRED: The destination Kafka server to connect to.")
                .withRequiredArg().describedAs("server to connect to").ofType(String.class);
            commandConfigOpt = parser.accepts("command-config", "Property file containing configs to be passed to Admin Client.")
                .withRequiredArg().describedAs("command config property file").ofType(String.class);
            mirrorOpt = parser.accepts("mirror", "The name of the cluster mirror.")
                .withRequiredArg().describedAs("mirror").ofType(String.class);
            mirrorConfigOpt = parser.accepts("mirror-config", "Property file containing source cluster configs for mirroring.")
                .withRequiredArg().describedAs("mirror config property file").ofType(String.class);
            topicsOpt = parser.accepts("topics", "Comma-separated list of topic names or regex patterns (e.g., 'my-topic,orders-.*,payments').")
                .withRequiredArg().describedAs("topics").ofType(String.class);
            createOpt = parser.accepts("create", "Create a new cluster mirror from a source cluster.");
            deleteOpt = parser.accepts("delete", "Delete a cluster mirror.");
            describeOpt = parser.accepts("describe", "Describe a cluster mirror including partition lag and state.");
            listOpt = parser.accepts("list", "List all cluster mirrors.");
            startOpt = parser.accepts("start", "Start mirroring topics matching the given patterns.");
            stopOpt = parser.accepts("stop", "Stop mirroring topics matching the given patterns.");
            pauseOpt = parser.accepts("pause", "Pause mirroring for topics matching the given patterns.");
            resumeOpt = parser.accepts("resume", "Resume mirroring for previously paused topics matching the given patterns.");
            recoverOpt = parser.accepts("recover", "Recover failed mirror partitions matching the given patterns.");
            failedOpt = parser.accepts("failed", "Show only failed partitions with error details.");
            jsonOpt = parser.accepts("json", "Output description in JSON format.");
            try {
                options = parser.parse(args);
            } catch (Exception e) {
                CommandLineUtils.printUsageAndExit(parser, e.getMessage());
            }
            checkArgs();
        }

        boolean has(joptsimple.OptionSpec<?> spec) {
            return options.has(spec);
        }

        <T> T valueOf(joptsimple.OptionSpec<T> spec) {
            return options.valueOf(spec);
        }

        List<String> topics() {
            return has(topicsOpt)
                ? Arrays.stream(valueOf(topicsOpt).split(",")).map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toList())
                : List.of();
        }

        private void checkArgs() {
            String description = "This tool helps to create cluster mirrors and manage mirror topics.";
            if (args.length == 0)
                CommandLineUtils.printUsageAndExit(parser, description);
            CommandLineUtils.maybePrintHelpOrVersion(this, description);
            CommandLineUtils.checkRequiredArgs(parser, options, bootstrapServerOpt);
            long actions = Stream.of(createOpt, deleteOpt, describeOpt, listOpt, startOpt, stopOpt, pauseOpt, resumeOpt, recoverOpt)
                .filter(options::has).count();
            if (actions != 1)
                CommandLineUtils.printUsageAndExit(parser, "Command must include exactly one action: --create, --delete, --describe, " +
                    "--list, --start, --stop, --pause, --resume or --recover.");
            if (Stream.of(createOpt, deleteOpt, startOpt, stopOpt, pauseOpt, resumeOpt, recoverOpt).anyMatch(options::has))
                CommandLineUtils.checkRequiredArgs(parser, options, mirrorOpt);
            if (options.has(createOpt))
                CommandLineUtils.checkRequiredArgs(parser, options, mirrorConfigOpt);
            if (Stream.of(startOpt, stopOpt, pauseOpt, resumeOpt, recoverOpt).anyMatch(options::has))
                CommandLineUtils.checkRequiredArgs(parser, options, topicsOpt);
        }
    }
}
