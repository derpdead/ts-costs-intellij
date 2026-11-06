package local.tscosts;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class CostsStartup implements StartupActivity.DumbAware {
    @Override
    public void runActivity(Project project) { CostsService.get(project).reload(false); }
}
