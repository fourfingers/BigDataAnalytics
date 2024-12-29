# Use a lightweight Python base image
FROM python:3.9-slim

# Set the working directory
WORKDIR /app

# Copy the requirements and application code
COPY requirements.txt .
COPY custom_mongodb_exporter.py .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Expose the Prometheus metrics port
EXPOSE 8000

# Command to run the exporter
CMD ["python", "custom_mongodb_exporter.py"]
