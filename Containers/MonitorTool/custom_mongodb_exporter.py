from prometheus_client import start_http_server, Gauge
from pymongo import MongoClient
import time
import os
import logging

# Configure logging
logging.basicConfig(
    format="time=%(asctime)s.%(msecs)03d level=%(levelname)s source=%(module)s.%(funcName)s msg=%(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
    level=logging.INFO
)

logger = logging.getLogger(__name__)

# Get MongoDB URI from the environment variable, with a default fallback
mongodb_uri = os.getenv("MONGODB_URI", "mongodb://localhost:27017")

# MongoDB connection
try:
    client = MongoClient(mongodb_uri)
    db = client['cloneDetector']
    logger.info(f"Successfully connected to MongoDB at {mongodb_uri}")
except Exception as e:
    logger.error(f"Failed to connect to MongoDB: {e}")
    exit(1)

# Prometheus metrics
files_count = Gauge('mongodb_files_count', 'Count of documents in files collection')
chunks_count = Gauge('mongodb_chunks_count', 'Count of documents in chunks collection')
candidates_count = Gauge('mongodb_candidates_count', 'Count of documents in candidates collection')
clones_count = Gauge('mongodb_clones_count', 'Count of documents in clones collection')

def collect_metrics():
    while True:
        try:
            files_count_value = db.files.count_documents({})
            chunks_count_value = db.chunks.count_documents({})
            candidates_count_value = db.candidates.count_documents({})
            clones_count_value = db.clones.count_documents({})

            files_count.set(files_count_value)
            chunks_count.set(chunks_count_value)
            candidates_count.set(candidates_count_value)
            clones_count.set(clones_count_value)

            logger.info(
                f"Metrics updated: files={files_count_value}, chunks={chunks_count_value}, "
                f"candidates={candidates_count_value}, clones={clones_count_value}"
            )
        except Exception as e:
            logger.error(f"Error collecting metrics: {e}")

        time.sleep(1)

if __name__ == '__main__':
    start_http_server(8000)
    logger.info("Prometheus metrics server started on port 8000")
    collect_metrics()
