package jadx.gui.ui.codearea;

import jadx.gui.ai.AiText;
import jadx.gui.treemodel.JNode;
import jadx.gui.ui.action.ActionModel;

/** The existing shortcut now opens an explanation without replacing source text. */
public final class TranslateAction extends JNodeAction {
	private static final long serialVersionUID = 1L;

	public TranslateAction(CodeArea codeArea) {
		super(ActionModel.AI_Translate, codeArea);
		setNameAndDesc(AiText.str("explain_action"));
	}

	@Override
	public void runAction(JNode node) {
		codeArea.getMainWindow().getAiWorkbench().open(codeArea, "explain");
	}

	@Override
	public boolean isActionEnabled(JNode node) {
		return codeArea != null && !codeArea.isDisposed() && codeArea.getJClass() != null;
	}
}
