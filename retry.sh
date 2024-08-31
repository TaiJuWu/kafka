I=0; while ./gradlew :metadata:test --tests "org.apache.kafka.controller.QuorumControllerTest.testUncleanShutdownBroker" --rerun --fail-fast; do (( I=$I+1 )); echo "Completed run: $I"; sleep 1; done
