package jadx.gui.ui.codearea;

import jadx.gui.treemodel.JNode;

import static jadx.gui.ui.action.ActionModel.AI_Audit;
import static jadx.gui.ui.action.ActionModel.AI_Translate;

/**
 * 安全审计菜单
 */
public final class AuditAction extends JNodeAction {


	public AuditAction(CodeArea codeArea) {
		super(AI_Audit, codeArea);
	}

	@Override
	public void runAction(JNode node) {

	}
	@Override
	public boolean isActionEnabled(JNode node) {
		return true;
	}

}
