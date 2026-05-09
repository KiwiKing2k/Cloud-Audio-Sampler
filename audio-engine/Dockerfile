FROM python:3.10-slim

# instalăm dependințele de sistem necesare pentru pedalboard
RUN apt-get update && apt-get install -y \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

# folderul de lucru în container
WORKDIR /app

# instalăm dependințele python
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# copiem codul sursă
COPY . .

# pornim serverul pe toate interfețele de rețea
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]