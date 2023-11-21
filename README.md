# Building Order Books using Dataflow

[//]: # ([![Open in Cloud Shell]&#40;https://gstatic.com/cloudssh/images/open-btn.svg&#41;]&#40;https://ssh.cloud.google.com/cloudshell/editor?cloudshell_git_repo=GITHUB_URL&#41;)

Demo code for processing ordered events in Apache Beam pipelines.

## Features

This repo contain a simulation of the Order Book processing in streaming and batch Apache Beam
pipelines.
It shows how ordering processing can be done in Apache Beam at scale, provides a fully functional
pipeline, a simulator test harness and a set of scripts to visualize processing steps and the output
of the pipeline.

## Use Case

TODO: describe

## Getting Started

1. Clone this repo and switch to the checked out directory
2. Designate or create a project to run the tests and create `terraform/terraform.tfvars` file with
   the following content:

```text
project_id = "<your project id>"
```

3. Create infrastructure to run the demo:

```shell
cd terraform
terraform init
terraform apply
cd ..
```

4. Build the project

```shell
mvn clean install
```

## Running the demo

### Start the test harness

This will start a simulator which will be generating synthetic orders and expected order book
events:

```shell
./start-simulator.sh
```

## Analyse the data

Once the pipeline is running, you can use BigQuery console, or `bq` utility to see how the pipeline
processes the data.

#### Processing state at a glance

To see the processing state for the latest session:

```sql
WITH latest_statuses AS (SELECT s.received_count,
                                s.buffered_count,
                                s.result_count,
                                s.duplicate_count,
                                s.last_event_received
                         FROM `ordered_processing_demo.processing_status` s
                         WHERE session_id = (SELECT DISTINCT session_id
                                             FROM `ordered_processing_demo.processing_status`
                                             ORDER BY session_id DESC
    LIMIT 1)
    QUALIFY RANK() OVER (PARTITION BY session_id
   , contract_id
ORDER BY status_ts DESC, received_count DESC) = 1 )
SELECT COUNT(*)                    total_contracts,
       COUNTIF(last_event_received
           AND buffered_count = 0) fully_processed,
       SUM(received_count)         total_orders_received,
       SUM(buffered_count)         total_orders_buffered,
       SUM(result_count)           total_results_produced,
       SUM(duplicate_count)        total_duplicates
FROM latest_statuses;
```

#### See the status of processing per each contract

This query shows processing status per contract for the latest session:

```sql
SELECT *
FROM `ordered_processing_demo.processing_status`
WHERE session_id = (SELECT DISTINCT session_id
                    FROM `ordered_processing_demo.processing_status`
                    ORDER BY session_id DESC
    LIMIT 1)
    QUALIFY RANK() OVER (PARTITION BY session_id
    , contract_id
ORDER BY status_ts DESC, received_count DESC) <= 5
ORDER BY
    session_id,
    contract_id,
    status_ts DESC,
    received_count DESC
    LIMIT 300
```

### Check out the latest market depths for each contract

```sql
SELECT *
FROM `ordered_processing_demo.market_depth` QUALIFY RANK() OVER (PARTITION BY session_id, contract_id ORDER BY session_id, contract_sequence_id DESC) <= 5
ORDER BY
    session_id,
    contract_id,
    contract_sequence_id DESC
    LIMIT
    300
```

## Cleanup

```shell
./stop-pipeline.sh
terraform -chdir terraform destroy 
```

## Steps required to create ordered processing

### Transform the data into the shape needed by the OrderedProcessing transform

Data needs to be in the `KV<GrouppingKey<KV<Long,Event>>` PCollection.

### Create a class which will take the first event and create a MutableState

This function needs to implement `ProcessFunction<EventType, MutableState>` interface. This function
is also a place where you can pass the parameters needed to initialize the state.

### Create a class which will examine each event

This class needs to implement EventExaminer interface.
Ordered processor will need to know when to start processing.
It also needs to know when the last event for a particular key has been received. This will help
indicate that all processing for a given key has been completed and allow stopping the process
status reporting and do the cleanup of the memory stored in the state.

### Create coders

Coders are needed by the OrderedProcessor transform to serialize and deserialize the data until it's
ready to be processed. There are multiple coders used by the transform:

* Mutable state coder. This coder will store the state of the order book. TODO: details
* Event coder. This coder will store the events to be buffered (out-of-sequence events)
* Key coder. In our case it's pretty simple. The key type is Long and there is an efficient coder
  for Longs - VarLongCoder.

### Create a custom transform to wrap the OrderedEventProcessing transform

This is an optional step and technically you don't need to do it. But if you do - the main pipeline
code will look more compact and the graph on the Dataflow UI will look "prettier".

### Decide where you would like to store the results of the processing

Our pipeline uses BigQuery tables to store the market depths produced by the order builder. You
would need to code classes that tranform MarketDepth to TableRows.

### Code the pipeline

The core processing of the pipeline is very simple at this point - read the sources, process them
and save the output.

## Limitations

### Duplicate detections

Currently, the processor will discard the events which have the order number lower than the
currently
processed order number. If the new event needs to be buffered the duplicate numbers won't be checked
and the processing results are unpredictable.

The number of detected duplicates will be reported in the emitted processing statuses.

## Additional Improvements

### Store only required elements in buffered objects

## Contributing

Contributions to this repo are always welcome and highly encouraged.

See [CONTRIBUTING](CONTRIBUTING.md) for more information how to get started.

Please note that this project is released with a Contributor Code of Conduct. By participating in
this project you agree to abide by its terms. See [Code of Conduct](CODE_OF_CONDUCT.md) for more
information.

## License

Apache 2.0 - See [LICENSE](LICENSE) for more information.