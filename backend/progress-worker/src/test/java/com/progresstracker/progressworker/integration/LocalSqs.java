package com.progresstracker.progressworker.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * An SQS-compatible broker (ElasticMQ) running in Docker, so the worker's real AWS SDK client
 * can be exercised without an AWS account. Started once per test run.
 */
final class LocalSqs {

    private static final int PORT = 9324;
    private static final String ACCOUNT_ID = "000000000000";

    private static final GenericContainer<?> BROKER =
            new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
                    .withExposedPorts(PORT)
                    .waitingFor(Wait.forLogMessage(".*ElasticMQ server.*started.*", 1));

    private static final SqsClient CLIENT;

    static {
        // The worker builds its client with the SDK's default credentials chain. The broker
        // ignores credentials, but the SDK still needs some to sign requests with.
        setIfAbsent("aws.accessKeyId", "test");
        setIfAbsent("aws.secretAccessKey", "test");

        BROKER.start();
        CLIENT = SqsClient.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private LocalSqs() {
    }

    static String endpoint() {
        return "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(PORT);
    }

    /** Creates a queue and returns a URL that is reachable from the test JVM. */
    static String createQueue(String name) {
        CLIENT.createQueue(request -> request.queueName(name));
        return urlFor(name);
    }

    /** Creates a queue that moves a message to the dead-letter queue after {@code maxReceiveCount} failed deliveries. */
    static String createQueueWithDeadLetter(String name, String deadLetterQueueUrl, int maxReceiveCount) {
        String deadLetterArn = CLIENT.getQueueAttributes(request -> request
                        .queueUrl(deadLetterQueueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + deadLetterArn + "\","
                + "\"maxReceiveCount\":\"" + maxReceiveCount + "\"}";

        CLIENT.createQueue(request -> request
                .queueName(name)
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy)));
        return urlFor(name);
    }

    static void send(String queueUrl, String body) {
        CLIENT.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body));
    }

    /** True when nothing is waiting on the queue and nothing is in flight with a consumer. */
    static boolean isDrained(String queueUrl) {
        Map<QueueAttributeName, String> attributes = CLIENT.getQueueAttributes(request -> request
                        .queueUrl(queueUrl)
                        .attributeNames(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
                .attributes();
        return attributes.values().stream().allMatch("0"::equals);
    }

    /** Reads message bodies without consuming them (they become visible again immediately). */
    static List<String> peekBodies(String queueUrl) {
        return CLIENT.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .visibilityTimeout(0))
                .messages()
                .stream()
                .map(Message::body)
                .toList();
    }

    private static String urlFor(String queueName) {
        return endpoint() + "/" + ACCOUNT_ID + "/" + queueName;
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
