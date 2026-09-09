package com.kartaguez.pocoma.pipeline.pot;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
public record ProjectPotTask(PipelineDefinition pipeline,PotId potId,long potVersion) implements TaskPayload {public ProjectPotTask{if(pipeline==null||potId==null)throw new NullPointerException();if(potVersion<1)throw new IllegalArgumentException();}}
