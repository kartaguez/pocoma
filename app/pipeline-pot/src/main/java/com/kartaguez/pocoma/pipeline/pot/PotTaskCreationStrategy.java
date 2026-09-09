package com.kartaguez.pocoma.pipeline.pot;
import static java.util.Objects.requireNonNull;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
public final class PotTaskCreationStrategy implements TaskCreationStrategy {
	private final PipelineDefinition pipeline;private final ObjectMapper mapper;
	public PotTaskCreationStrategy(PipelineDefinition p,ObjectMapper m){pipeline=requireNonNull(p);mapper=requireNonNull(m);if(!PotProjectionPipeline.PIPELINE_ID.equals(p.pipelineId().value()))throw new IllegalArgumentException();}
	@Override public PipelineDefinition definition(){return pipeline;} @Override public boolean supports(BusinessEvent e){return e!=null;}
	@Override public List<TaskDescriptor> createTasks(BusinessEvent e){requireNonNull(e);String id=e.potId().value().toString();try{return List.of(new TaskDescriptor(PotProjectionPipeline.TASK_TYPE,id+":"+e.version(),mapper.writeValueAsString(new Payload(pipeline.pipelineId().value(),pipeline.pipelineVersion(),id,e.version())),id,e.version()));}catch(Exception x){throw new IllegalStateException("Unable to serialize Pot task",x);}}
	private record Payload(String pipelineId,int pipelineVersion,String potId,long potVersion){}
}
