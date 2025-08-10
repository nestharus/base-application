import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Properties;

public class FlinkTask {
    
    public static class MessageProcessor implements MapFunction<String, String> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        
        @Override
        public String map(String value) throws Exception {
            try {
                ObjectNode inputJson = (ObjectNode) objectMapper.readTree(value);
                String message = inputJson.get("message").asText("");
                
                ObjectNode outputJson = objectMapper.createObjectNode();
                outputJson.put("response", "You sent " + message);
                
                return objectMapper.writeValueAsString(outputJson);
            } catch (Exception e) {
                ObjectNode errorJson = objectMapper.createObjectNode();
                errorJson.put("error", "Invalid JSON input");
                return objectMapper.writeValueAsString(errorJson);
            }
        }
    }
    
    public static void main(String[] args) throws Exception {
        // Set up the execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        
        // Kafka properties
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
        kafkaProps.setProperty("group.id", "flink-java-consumer");
        
        // Create Kafka consumer
        FlinkKafkaConsumer<String> kafkaConsumer = new FlinkKafkaConsumer<>(
            "input-topic",
            new SimpleStringSchema(),
            kafkaProps
        );
        
        // Create Kafka producer
        FlinkKafkaProducer<String> kafkaProducer = new FlinkKafkaProducer<>(
            "output-topic",
            new SimpleStringSchema(),
            kafkaProps
        );
        
        // Create data stream from Kafka
        DataStream<String> inputStream = env.addSource(kafkaConsumer);
        
        // Process the stream
        DataStream<String> processedStream = inputStream.map(new MessageProcessor());
        
        // Send processed data to Kafka
        processedStream.addSink(kafkaProducer);
        
        // Execute the Flink job
        env.execute("Java Flink Message Processor");
    }
}