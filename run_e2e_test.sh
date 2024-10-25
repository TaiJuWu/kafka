TC_PATHS="tests/kafkatest/tests/client/consumer_test.py::OffsetValidationTest.test_broker_failure" \
DUCKTAPE_OPTIONS='--parameters '\''{clean_shutdown=False.enable_autocommit=True.metadata_quorum=ISOLATED_KRAFT.use_new_coordinator=True.group_protocol=consumer}'\' \
bash tests/docker/run_tests.sh
