package eu.cqse.teamscale.jacoco.client.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.function.Predicate;

import org.junit.Test;

import eu.cqse.teamscale.jacoco.client.agent.AgentOptions.AgentOptionParseException;

/** Tests the {@link AgentOptions}. */
public class AgentOptionsTest {

	/** Tests path to class name conversion */
	@Test
	public void testPackageNames() {
		assertThat(getClassName("file.jar@com/foo/Bar.class")).isEqualTo("com.foo.Bar");
		assertThat(getClassName("file.jar@com/foo/Bar$Goo.class")).isEqualTo("com.foo.Bar.Goo");
		assertThat(getClassName("file1.jar@goo/file2.jar@com/foo/Bar.class")).isEqualTo("com.foo.Bar");
		assertThat(getClassName("com/foo/Bar.class")).isEqualTo("com.foo.Bar");
	}

	/** Tests include pattern matching. */
	@Test
	public void testIncludes() throws AgentOptionParseException {
		assertThat(includeFilter("com.*")).accepts("file.jar@com/foo/Bar.class", "file.jar@com/foo/Bar$Goo.class",
				"file1.jar@goo/file2.jar@com/foo/Bar.class", "com/foo/Bar.class", "com.foo/Bar.class");
		assertThat(includeFilter("com.*")).rejects("foo/com/Bar.class", "com.class", "file.jar@com.class",
				"A$com$Bar.class");
		assertThat(includeFilter("*com.*")).accepts("file.jar@com/foo/Bar.class", "file.jar@com/foo/Bar$Goo.class",
				"file1.jar@goo/file2.jar@com/foo/Bar.class", "com/foo/Bar.class", "foo/com/goo/Bar.class",
				"A$com$Bar.class", "src/com/foo/Bar.class");
	}

	/** Returns the include filter predicate for the given filter expression. */
	private static Predicate<String> includeFilter(String filterString) throws AgentOptionParseException {
		AgentOptions agentOptions = new AgentOptions("out=.,class-dir=.,includes=" + filterString);
		return string -> agentOptions.getLocationIncludeFilter().test(Paths.get(string));
	}

	/** Returns the class name for the given file path. */
	private static String getClassName(String classFilePath) {
		return AgentOptions.getClassName(Paths.get(classFilePath));
	}

}
