package jadx.gui.ui.codearea;

import jadx.gui.ai.AiText;
import jadx.gui.treemodel.JNode;
import jadx.gui.ui.action.ActionModel;

public final class AuditAction extends JNodeAction {
	private static final long serialVersionUID = 1L;

	public AuditAction(CodeArea codeArea) {
		super(ActionModel.AI_Audit, codeArea);
		setNameAndDesc(AiText.str("audit_action"));
	}

	@Override
	public void runAction(JNode node) {
		codeArea.getMainWindow().getAiWorkbench().open(codeArea, "audit");
	}

	@Override
	public boolean isActionEnabled(JNode node) {
		return codeArea != null && !codeArea.isDisposed() && codeArea.getJClass() != null;
	}
}
