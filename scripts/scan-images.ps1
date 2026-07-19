param([string]$Tag = "docpilot-agent-service:local")
docker build -t $Tag ./agent-service
docker run --rm aquasec/trivy:0.58.1 image --severity HIGH,CRITICAL --ignore-unfixed $Tag
