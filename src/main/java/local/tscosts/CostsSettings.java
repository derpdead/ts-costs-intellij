package local.tscosts;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
@State(name = "TsCosts", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class CostsSettings implements PersistentStateComponent<CostsSettings.StateData> {
    public static final class StateData {
        public String projectRoot = "";
        public String node = "";
        public String config = "";
        public boolean inlays = true;
    }
    private StateData state = new StateData();
    public static CostsSettings get(Project project) { return project.getService(CostsSettings.class); }
    @Override public StateData getState() { return state; }
    @Override public void loadState(StateData value) { state = value; }
}
