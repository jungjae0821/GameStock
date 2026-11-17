package com.gamestock.backend.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 외부 사이트를 읽는 예약 작업(뉴스 RSS, 공식 공지, 기사 발췌, 한강 수온)을 따로 돌리는 작업자.
 *
 * 예약 작업은 스케줄러 스레드 8개를 함께 쓴다. RSS 수집처럼 요청 하나에 최대 15초씩 기다리는 작업이 그
 * 스레드를 붙잡으면 100ms 시세 방송·손절 감시 같은 작업이 밀린다. 그래서 예약 작업은 여기에 일을 넘기고
 * 바로 돌아간다. TaskScheduler 빈을 새로 만들면 Spring Boot가 기본 스케줄러를 만들지 않으므로 일반 실행기로 둔다.
 */
@Component
public class NetworkJobs {
    private static final Logger log = LoggerFactory.getLogger(NetworkJobs.class);
    private final AtomicInteger threads = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "network-jobs-" + threads.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** 같은 이름의 작업이 아직 돌고 있으면 이번 차례는 건너뛴다. 예약 작업이 겹쳐 쌓이지 않게 한다. */
    public void submit(String name, Runnable job) {
        if (!running.add(name)) return;
        try {
            workers.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException error) {
                    log.warn("{} 작업이 실패했습니다.", name, error);
                } finally {
                    running.remove(name);
                }
            });
        } catch (RejectedExecutionException closing) {
            running.remove(name);
        }
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
    }
}
