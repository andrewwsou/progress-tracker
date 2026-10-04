package com.progresstracker.progresstracker.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
import java.util.List;

/**
 * An SQS-compatible broker (ElasticMQ) running in Docker, so the real AWS SDK client in the
 * application can be exercised without an AWS account. Started once per test run.
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
        // The application builds its client with the SDK's default credentials chain. The broker
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
        return endpoint() + "/" + ACCOUNT_ID + "/" + name;
    }

    /** Removes a queue, to simulate the queue being unavailable. */
    static void deleteQueue(String queueUrl) {
        CLIENT.deleteQueue(request -> request.queueUrl(queueUrl));
    }

    static List<Message> receive(String queueUrl) {
        return CLIENT.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1))
                .messages();
    }

    static void delete(String queueUrl, Message message) {
        CLIENT.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
