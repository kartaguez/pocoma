package com.kartaguez.pocoma;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.kartaguez.pocoma.pipelinetask.mapping.ComputeBalancesRecordedTaskMapper;
import com.kartaguez.pocoma.supra.worker.task.TaskWorker;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "pocoma.pipeline.task-execution.enabled=false")
class PocomaPipelineTaskExecutorApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		assertNotNull(context.getBean(ComputeBalancesRecordedTaskMapper.class));
		assertNotNull(context.getBean(RecordedTaskExecutionMapperRegistry.class));
		assertEquals(1, context.getBeansOfType(ComputeBalancesRecordedTaskMapper.class).size());
		assertEquals(0, context.getBeansOfType(TaskWorker.class).size());
	}
}
