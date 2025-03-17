package jadx.gui.ui.codearea;

import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JRenameNode;
import jadx.gui.ui.action.ActionModel;

import static jadx.gui.ui.action.ActionModel.AI_Translate;

public final class TranslateAction extends JNodeAction {


	public TranslateAction(CodeArea codeArea) {
		super(AI_Translate, codeArea);
	}

	@Override
	public void runAction(JNode node) {

	}
	@Override
	public boolean isActionEnabled(JNode node) {
		return true;
	}

}
