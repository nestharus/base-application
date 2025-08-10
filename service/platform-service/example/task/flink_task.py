from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors import FlinkKafkaConsumer, FlinkKafkaProducer
from pyflink.common.serialization import SimpleStringSchema
from pyflink.datastream.functions import MapFunction
import json


class MessageProcessor(MapFunction):
    def map(self, value):
        try:
            data = json.loads(value)
            message = data.get("message", "")
            result = f"You sent {message}"
            return json.dumps({"response": result})
        except json.JSONDecodeError:
            return json.dumps({"error": "Invalid JSON input"})


def main():
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    
    # Example Kafka source configuration (adjust as needed)
    kafka_props = {
        'bootstrap.servers': 'localhost:9092',
        'group.id': 'flink-python-consumer'
    }
    
    # Create Kafka consumer
    kafka_consumer = FlinkKafkaConsumer(
        topics='input-topic',
        deserialization_schema=SimpleStringSchema(),
        properties=kafka_props
    )
    
    # Create Kafka producer
    kafka_producer = FlinkKafkaProducer(
        topic='output-topic',
        serialization_schema=SimpleStringSchema(),
        producer_config=kafka_props
    )
    
    # Process stream
    data_stream = env.add_source(kafka_consumer)
    
    processed_stream = data_stream.map(MessageProcessor())
    
    processed_stream.add_sink(kafka_producer)
    
    # Execute the job
    env.execute("Python Flink Message Processor")


if __name__ == "__main__":
    main()