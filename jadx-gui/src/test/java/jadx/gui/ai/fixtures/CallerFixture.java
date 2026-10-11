package jadx.gui.ai.fixtures;

public class CallerFixture {
	public static class Inner {
		public String nested() {
			return "nested";
		}
	}

	private String saved;

	public void store(String value) {
		saved = value;
	}

	public void first() {
		store("first");
	}

	public void second() {
		store("second");
	}

	public void third() {
		store("third");
	}

	public void fourth() {
		store("fourth");
	}

	public String read() {
		return saved;
	}
}
