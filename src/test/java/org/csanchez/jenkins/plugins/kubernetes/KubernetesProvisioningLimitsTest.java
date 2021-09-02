package org.csanchez.jenkins.plugins.kubernetes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

public class KubernetesProvisioningLimitsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule log = new LoggerRule().record(KubernetesProvisioningLimits.class, Level.FINEST);

    @Test
    public void lotsOfCloudsAndTemplates() throws InterruptedException {
        ThreadLocalRandom testRandom = ThreadLocalRandom.current();
        for (int i = 1; i < 4; i++) {
            KubernetesCloud cloud = new KubernetesCloud("kubernetes-" + i);
            cloud.setContainerCap(testRandom.nextInt(4)+1);
            for (int j = 1; j < 4; j++) {
                PodTemplate pt = new PodTemplate();
                pt.setName(cloud.name + "-podTemplate-" + j);
                pt.setInstanceCap(testRandom.nextInt(4)+1);
                cloud.addTemplate(pt);
            }
            j.jenkins.clouds.add(cloud);
        }

        ExecutorService threadPool = Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors()*4);
        CompletionService<Void> ecs = new ExecutorCompletionService<>(threadPool);
        KubernetesProvisioningLimits kubernetesProvisioningLimits = KubernetesProvisioningLimits.get();

        List<KubernetesCloud> clouds = j.jenkins.clouds.getAll(KubernetesCloud.class);
        for (int k = 0 ; k < 100000; k++) {
            ecs.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                KubernetesCloud cloud = clouds.get(random.nextInt(clouds.size()));
                List<PodTemplate> templates = cloud.getTemplates();
                PodTemplate podTemplate = templates.get(random.nextInt(templates.size()));
                while (!kubernetesProvisioningLimits.register(cloud, podTemplate, 1)) {
                    Thread.yield();
                }

                ecs.submit(() -> {
                    kubernetesProvisioningLimits.unregister(cloud, podTemplate, 1);
                }, null);
            }, null);
        }

        while (ecs.poll(2, TimeUnit.SECONDS) != null) {
            Thread.yield();
        }

        threadPool.shutdown();
        assertTrue(threadPool.awaitTermination(10, TimeUnit.SECONDS));

        // Check that every count is back to 0
        for (KubernetesCloud cloud : j.jenkins.clouds.getAll(KubernetesCloud.class)) {
            assertEquals(0, KubernetesProvisioningLimits.get().getGlobalCount(cloud.name));
            for (PodTemplate template : cloud.getTemplates()) {
                assertEquals(0, KubernetesProvisioningLimits.get().getPodTemplateCount(template.getId()));
            }
        }
    }
}
