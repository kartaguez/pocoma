package com.kartaguez.pocoma.pipeline.pot;
import static java.util.Objects.requireNonNull;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure;
public final class ProjectPotRecordedTaskMapper implements RecordedTaskExecutionMapper<ProjectPotTask>{
	private final PipelineDefinition pipeline;private final ObjectMapper mapper;
	public ProjectPotRecordedTaskMapper(PipelineDefinition p,ObjectMapper m){pipeline=requireNonNull(p);mapper=requireNonNull(m);}
	@Override public PipelineDefinition pipeline(){return pipeline;}@Override public String taskType(){return PotProjectionPipeline.TASK_TYPE;}
	@Override public ExecuteTaskInput<ProjectPotTask> map(RecordedTask task){try{if(!pipeline.equals(task.pipeline())||!taskType().equals(task.taskType()))throw new InvalidPotTaskException("Task binding mismatch");var p=mapper.readValue(task.serializedPayload(),Payload.class);var pp=new PipelineDefinition(PipelineId.of(p.pipelineId()),p.pipelineVersion());var pot=PotId.of(UUID.fromString(p.potId()));if(!pipeline.equals(pp)||!pot.equals(task.potId())||p.potVersion()!=task.targetVersion())throw new InvalidPotTaskException("Task payload mismatch");return new ExecuteTaskInput<>(pipeline,taskType(),new ProjectPotTask(pp,pot,p.potVersion()));}catch(InvalidPotTaskException e){throw e;}catch(Exception e){throw new InvalidPotTaskException("Invalid Pot task payload",e);}}
	private record Payload(String pipelineId,int pipelineVersion,String potId,long potVersion){}
	public static final class InvalidPotTaskException extends RuntimeException implements NonRetryableTaskTechnicalFailure{public InvalidPotTaskException(String m){super(m);}public InvalidPotTaskException(String m,Throwable c){super(m,c);}@Override public String failureCode(){return "INVALID_TASK_PAYLOAD";}@Override public String failureCategory(){return "INVALID_TASK_PAYLOAD";}}
}
