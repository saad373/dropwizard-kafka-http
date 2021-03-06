package ly.stealth.kafkahttp;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.base.Strings;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.charset.Charset;
import java.util.*;

@Path("/message")
@Produces(MediaType.TEXT_PLAIN)
public class MessageResource {
    private KafkaProducer producer;
    private Properties consumerCfg;

    public MessageResource(KafkaProducer producer, Properties consumerCfg) {
        this.producer = producer;
        this.consumerCfg = consumerCfg;
    }

    @POST
    @Timed
    public Response produce(
            @FormParam("topic") String topic,
            @FormParam("key") List<String> keys,
            @FormParam("message") List<String> messages
    ) {
        List<String> errors = new ArrayList<>();
        if (Strings.isNullOrEmpty(topic)) errors.add("Undefined topic");

        if (messages.isEmpty()) errors.add("Undefined message");
        if (!keys.isEmpty() && keys.size() != messages.size()) errors.add("Messages count != keys count");

        if (!errors.isEmpty())
            return Response.status(400)
                    .entity(errors)
                    .build();

        Charset charset = Charset.forName("utf-8");
        for (int i = 0; i < messages.size(); i++) {
            String key = keys.isEmpty() ? null : keys.get(i);
            String message = messages.get(i);
            producer.send(new ProducerRecord(topic, key != null ? key.getBytes(charset) : null, message.getBytes(charset)));
        }

        return Response.ok().build();
    }

    @GET
    @Timed
    public Response consume(
            @QueryParam("topic") String topic,
            @QueryParam("timeout") Integer timeout
    ) {
        if (Strings.isNullOrEmpty(topic))
            return Response.status(400)
                    .entity(new String[]{"Undefined topic"})
                    .build();

        Properties props = (Properties) consumerCfg.clone();
        if (timeout != null) props.put("consumer.timeout.ms", "" + timeout);

        ConsumerConfig config = new ConsumerConfig(props);
        ConsumerConnector connector = Consumer.createJavaConsumerConnector(config);

        Map<String, Integer> streamCounts = Collections.singletonMap(topic, 1);
        Map<String, List<KafkaStream<byte[], byte[]>>> streams = connector.createMessageStreams(streamCounts);
        KafkaStream<byte[], byte[]> stream = streams.get(topic).get(0);

        List<Message> messages = new ArrayList<>();
        try {
            for (MessageAndMetadata<byte[], byte[]> messageAndMetadata : stream)
                messages.add(new Message(messageAndMetadata));
        } catch (ConsumerTimeoutException ignore) {
        } finally {
            connector.commitOffsets();
            connector.shutdown();
        }

        return Response.ok(messages).build();
    }

    public static class Message {
        public String topic;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String key;
        public String message;

        public int partition;
        public long offset;

        public Message(MessageAndMetadata<byte[], byte[]> message) {
            this.topic = message.topic();

            this.key = message.key() != null ? new String(message.key(), Charset.forName("utf-8")) : null;
            this.message = new String(message.message(), Charset.forName("utf-8"));

            this.partition = message.partition();
            this.offset = message.offset();
        }
    }
}
