// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.rules.genrule;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.GENRULE_SETUP;
import static com.google.devtools.build.lib.testutil.TestConstants.GENRULE_SETUP_PATH;
import static org.junit.Assert.fail;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link BazelGenRule}. */
@RunWith(JUnit4.class)
public class GenRuleConfiguredTargetTest extends BuildViewTestCase {

  /** Filter to remove implicit dependencies of C/C++ rules. */
  private static final Predicate<ConfiguredTarget> CC_CONFIGURED_TARGET_FILTER =
      new Predicate<ConfiguredTarget>() {
        @Override
        public boolean apply(ConfiguredTarget target) {
          return AnalysisMock.get().ccSupport().labelFilter().apply(target.getLabel());
        }
      };

  /** Filter to remove implicit dependencies of Java rules. */
  private static final Predicate<ConfiguredTarget> JAVA_CONFIGURED_TARGET_FILTER =
      new Predicate<ConfiguredTarget>() {
        @Override
        public boolean apply(ConfiguredTarget target) {
          Label label = target.getLabel();
          String labelName = "//" + label.getPackageName();
          return !labelName.startsWith("//third_party/java/jdk");
        }
      };

  private static final Pattern SETUP_COMMAND_PATTERN =
      Pattern.compile(".*/genrule-setup.sh;\\s+(?<command>.*)");

  private void assertCommandEquals(String expected, String command) {
    // Ensure the command after the genrule setup is correct.
    Matcher m = SETUP_COMMAND_PATTERN.matcher(command);
    if (m.matches()) {
      command = m.group("command");
    }

    assertThat(command).isEqualTo(expected);
  }

  public void createFiles() throws Exception {
    scratch.file(
        "hello/BUILD",
        "genrule(",
        "    name = 'z',",
        "    outs = ['x/y'],",
        "    cmd = 'echo hi > $(@D)/y',",
        ")",
        "genrule(",
        "    name = 'w',",
        "    outs = ['a/b', 'c/d'],",
        "    cmd = 'echo hi | tee $(@D)/a/b $(@D)/c/d',",
        ")");
  }

  @Override
  protected ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    return builder.addRuleDefinition(new TestRuleClassProvider.MakeVariableTesterRule()).build();
  }

  @Test
  public void testToolchainMakeVariableExpansion() throws Exception {
    scratch.file("a/BUILD",
        "genrule(name='gr', srcs=[], outs=['out'], cmd='$(TEST_VARIABLE)', toolchains=[':v'])",
        "make_variable_tester(name='v')");

    String cmd = getCommand("//a:gr");
    assertThat(cmd).endsWith("FOOBAR");
  }

  @Test
  public void testD() throws Exception {
    createFiles();
    ConfiguredTarget z = getConfiguredTarget("//hello:z");
    Artifact y = getOnlyElement(getFilesToBuild(z));
    assertThat(y.getRootRelativePath()).isEqualTo(PathFragment.create("hello/x/y"));
  }

  @Test
  public void testDMultiOutput() throws Exception {
    createFiles();
    ConfiguredTarget z = getConfiguredTarget("//hello:w");
    List<Artifact> files = getFilesToBuild(z).toList();
    assertThat(files).hasSize(2);
    assertThat(files.get(0).getRootRelativePath()).isEqualTo(PathFragment.create("hello/a/b"));
    assertThat(files.get(1).getRootRelativePath()).isEqualTo(PathFragment.create("hello/c/d"));
  }

  @Test
  public void testOutsWithSameNameAsRule() throws Exception {
    // The error was demoted to a warning.
    // Re-enable after June 1 2008 when we make it an error again.
    checkWarning(
        "genrule2",
        "hello_world",
        "target 'hello_world' is both a rule and a file;",
        "genrule(name = 'hello_world',",
        "srcs = ['ignore_me.txt'],",
        "outs = ['message.txt', 'hello_world'],",
        "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')");
  }

  @Test
  public void testFilesToBuildIsOuts() throws Exception {
    scratch.file(
        "genrule1/BUILD",
        "genrule(name = 'hello_world',",
        "srcs = ['ignore_me.txt'],",
        "outs = ['message.txt'],",
        "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')");
    Artifact messageArtifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact();
    assertThat(getFilesToBuild(getConfiguredTarget("//genrule1:hello_world")))
        .containsExactly(messageArtifact);
  }

  @Test
  public void testActionIsShellCommand() throws Exception {
    scratch.file(
        "genrule1/BUILD",
        "genrule(name = 'hello_world',",
        "srcs = ['ignore_me.txt'],",
        "outs = ['message.txt'],",
        "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')");

    Artifact messageArtifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact();
    SpawnAction shellAction = (SpawnAction) getGeneratingAction(messageArtifact);

    Artifact ignoreMeArtifact = getFileConfiguredTarget("//genrule1:ignore_me.txt").getArtifact();
    Artifact genruleSetupArtifact = getFileConfiguredTarget(GENRULE_SETUP).getArtifact();

    assertThat(shellAction).isNotNull();
    assertThat(shellAction.getInputs()).containsExactly(ignoreMeArtifact, genruleSetupArtifact);
    assertThat(shellAction.getOutputs()).containsExactly(messageArtifact);

    String expected = "echo \"Hello, world.\" >" + messageArtifact.getExecPathString();
    assertThat(shellAction.getArguments().get(0))
        .isEqualTo(targetConfig.getShellExecutable().getPathString());
    assertThat(shellAction.getArguments().get(1)).isEqualTo("-c");
    assertCommandEquals(expected, shellAction.getArguments().get(2));
  }

  @Test
  public void testDependentGenrule() throws Exception {
    scratch.file(
        "genrule1/BUILD",
        "genrule(name = 'hello_world',",
        "srcs = ['ignore_me.txt'],",
        "outs = ['message.txt'],",
        "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')");
    scratch.file(
        "genrule2/BUILD",
        "genrule(name = 'goodbye_world',",
        "srcs = ['goodbye.txt', '//genrule1:hello_world'],",
        "outs = ['farewell.txt'],",
        "cmd  = 'echo $(SRCS) >$(location farewell.txt)')");

    getConfiguredTarget("//genrule2:goodbye_world");

    Artifact farewellArtifact = getFileConfiguredTarget("//genrule2:farewell.txt").getArtifact();
    Artifact goodbyeArtifact = getFileConfiguredTarget("//genrule2:goodbye.txt").getArtifact();
    Artifact messageArtifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact();
    Artifact genruleSetupArtifact = getFileConfiguredTarget(GENRULE_SETUP).getArtifact();

    SpawnAction shellAction = (SpawnAction) getGeneratingAction(farewellArtifact);

    // inputs = { "goodbye.txt", "//genrule1:message.txt" }
    assertThat(shellAction.getInputs())
        .containsExactly(goodbyeArtifact, messageArtifact, genruleSetupArtifact);

    // outputs = { "farewell.txt" }
    assertThat(shellAction.getOutputs()).containsExactly(farewellArtifact);

    String expected =
        "echo "
            + goodbyeArtifact.getExecPathString()
            + " "
            + messageArtifact.getExecPathString()
            + " >"
            + farewellArtifact.getExecPathString();
    assertCommandEquals(expected, shellAction.getArguments().get(2));
  }

  /**
   * Ensure that the actions / artifacts created by genrule dependencies allow us to follow the
   * chain of generated files backward.
   */
  @Test
  public void testDependenciesViaFiles() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'bar',",
        "        srcs = ['bar_in.txt'],",
        "        cmd = 'touch $(OUTS)',",
        "        outs = ['bar_out.txt'])",
        "genrule(name = 'baz',",
        "        srcs = ['bar_out.txt'],",
        "        cmd = 'touch $(OUTS)',",
        "        outs = ['baz_out.txt'])");

    FileConfiguredTarget bazOutTarget = getFileConfiguredTarget("//foo:baz_out.txt");
    Action bazAction = getGeneratingAction(bazOutTarget.getArtifact());
    Artifact barOut = bazAction.getInputs().iterator().next();
    assertThat(barOut.getExecPath().endsWith(PathFragment.create("foo/bar_out.txt"))).isTrue();
    Action barAction = getGeneratingAction(barOut);
    Artifact barIn = barAction.getInputs().iterator().next();
    assertThat(barIn.getExecPath().endsWith(PathFragment.create("foo/bar_in.txt"))).isTrue();
  }

  /** Ensure that variable $(@D) gets expanded correctly in the genrule cmd. */
  @Test
  public void testOutputDirExpansion() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'bar',",
        "        srcs = ['bar_in.txt'],",
        "        cmd = 'touch $(@D)',",
        "        outs = ['bar/bar_out.txt'])",
        "genrule(name = 'baz',",
        "        srcs = ['bar/bar_out.txt'],",
        "        cmd = 'touch $(@D)',",
        "        outs = ['logs/baz_out.txt', 'logs/baz.log'])");

    getConfiguredTarget("//foo:bar");

    FileConfiguredTarget bazOutTarget = getFileConfiguredTarget("//foo:logs/baz_out.txt");

    SpawnAction bazAction = (SpawnAction) getGeneratingAction(bazOutTarget.getArtifact());

    // Make sure the expansion for $(@D) results in the
    // directory of the BUILD file ("foo"), not the common parent
    // directory of the output files ("logs")
    String bazExpected =
        "touch "
            + bazOutTarget
                .getArtifact()
                .getExecPath()
                .getParentDirectory()
                .getParentDirectory()
                .getPathString();
    assertCommandEquals(bazExpected, bazAction.getArguments().get(2));
    assertThat(bazAction.getArguments().get(2)).endsWith("/foo");

    getConfiguredTarget("//foo:bar");

    Artifact barOut = bazAction.getInputs().iterator().next();
    assertThat(barOut.getExecPath().endsWith(PathFragment.create("foo/bar/bar_out.txt"))).isTrue();
    SpawnAction barAction = (SpawnAction) getGeneratingAction(barOut);
    String barExpected = "touch " + barOut.getExecPath().getParentDirectory().getPathString();
    assertCommandEquals(barExpected, barAction.getArguments().get(2));
    assertThat(bazExpected.equals(barExpected)).isFalse();
  }

  /** Ensure that variable $(CC) gets expanded correctly in the genrule cmd. */
  @Test
  public void testMakeVarExpansion() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'bar',",
        "        srcs = ['bar.cc'],",
        "        cmd = '$(CC) -o $(OUTS) $(SRCS) $$shellvar',",
        "        outs = ['bar.o'])");
    FileConfiguredTarget barOutTarget = getFileConfiguredTarget("//foo:bar.o");
    FileConfiguredTarget barInTarget = getFileConfiguredTarget("//foo:bar.cc");

    SpawnAction barAction = (SpawnAction) getGeneratingAction(barOutTarget.getArtifact());

    String cc = "" + targetConfig.getFragment(CppConfiguration.class).getCppExecutable();
    String expected =
        cc
            + " -o "
            + barOutTarget.getArtifact().getExecPathString()
            + " "
            + barInTarget.getArtifact().getRootRelativePath().getPathString()
            + " $shellvar";
    assertCommandEquals(expected, barAction.getArguments().get(2));
  }

  @Test
  public void onlyHasCcToolchainDepWhenCcMakeVariablesArePresent() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'no_cc',",
        "        srcs = [],",
        "        cmd = 'echo no CC variables here > $@',",
        "        outs = ['no_cc.out'])",
        "genrule(name = 'cc',",
        "        srcs = [],",
        "        cmd = 'echo $(CC) > $@',",
        "        outs = ['cc.out'])");
    String ccToolchainAttr = ":cc_toolchain";
    assertThat(getPrerequisites(getConfiguredTarget("//foo:no_cc"), ccToolchainAttr)).isEmpty();
    assertThat(getPrerequisites(getConfiguredTarget("//foo:cc"), ccToolchainAttr)).isNotEmpty();
  }

  /** Ensure that Java make variables get expanded under the *host* configuration. */
  @Test
  public void testJavaMakeVarExpansion() throws Exception {
    String ruleTemplate =
        "genrule(name = '%s',"
            + "  srcs = [],"
            + "  cmd = 'echo $(%s) > $@',"
            + "  outs = ['%s'])";

    scratch.file(
        "foo/BUILD",
        String.format(ruleTemplate, "java_rule", "JAVA", "java.txt"),
        String.format(ruleTemplate, "javabase_rule", "JAVABASE", "javabase.txt"));

    Artifact javaOutput = getFileConfiguredTarget("//foo:java.txt").getArtifact();
    Artifact javabaseOutput = getFileConfiguredTarget("//foo:javabase.txt").getArtifact();

    String javaCommand =
        ((SpawnAction) getGeneratingAction(javaOutput)).getArguments().get(2);
    assertThat(javaCommand).containsMatch("jdk/bin/java(.exe)? >");

    String javabaseCommand =
        ((SpawnAction) getGeneratingAction(javabaseOutput)).getArguments().get(2);
    assertThat(javabaseCommand).contains("jdk >");
  }

  // Returns the expansion of 'cmd' for the specified genrule.
  private String getCommand(String label) throws Exception {
    return getSpawnAction(label).getArguments().get(2);
  }

  // Returns the SpawnAction for the specified genrule.
  private SpawnAction getSpawnAction(String label) throws Exception {
    return (SpawnAction)
        getGeneratingAction(getFilesToBuild(getConfiguredTarget(label)).iterator().next());
  }

  @Test
  public void testMessage() throws Exception {
    scratch.file(
        "genrule3/BUILD",
        "genrule(name = 'hello_world',",
        "    srcs = ['ignore_me.txt'],",
        "    outs = ['hello.txt'],",
        "    cmd  = 'echo \"Hello, world.\" >hello.txt')",
        "genrule(name = 'goodbye_world',",
        "    srcs = ['ignore_me.txt'],",
        "    outs = ['goodbye.txt'],",
        "    message = 'Generating message',",
        "    cmd  = 'echo \"Goodbye, world.\" >goodbye.txt')");
    assertThat(getSpawnAction("//genrule3:hello_world").getProgressMessage())
        .isEqualTo("Executing genrule //genrule3:hello_world");
    assertThat(getSpawnAction("//genrule3:goodbye_world").getProgressMessage())
        .isEqualTo("Generating message //genrule3:goodbye_world");
  }

  /** Ensure that labels from binary targets expand to the executable */
  @Test
  public void testBinaryTargetsExpandToExecutable() throws Exception {
    scratch.file(
        "genrule3/BUILD",
        "genrule(name = 'hello_world',",
        "    srcs = ['ignore_me.txt'],",
        "    tools = ['echo'],",
        "    outs = ['message.txt'],",
        "    cmd  = '$(location :echo) \"Hello, world.\" >message.txt')",
        "cc_binary(name = 'echo',",
        "    srcs = ['echo.cc'])");
    String regex = "b.{4}-out/.*/bin/genrule3/echo(\\.exe)? \"Hello, world.\" >message.txt";
    assertThat(getCommand("//genrule3:hello_world")).containsMatch(regex);
  }

  @Test
  public void testOutputToBindir() throws Exception {
    scratch.file(
        "x/BUILD",
        "genrule(name='bin', ",
        "        outs=['bin.out'],",
        "        cmd=':',",
        "        output_to_bindir=1)",
        "genrule(name='genfiles', ",
        "        outs=['genfiles.out'],",
        "        cmd=':',",
        "        output_to_bindir=0)");

    assertThat(getFileConfiguredTarget("//x:bin.out").getArtifact())
        .isEqualTo(getBinArtifact("bin.out", "//x:bin"));
    assertThat(getFileConfiguredTarget("//x:genfiles.out").getArtifact())
        .isEqualTo(getGenfilesArtifact("genfiles.out", "//x:genfiles"));
  }

  @Test
  public void testMultipleOutputsToBindir() throws Exception {
    scratch.file(
        "x/BUILD",
        "genrule(name='bin', ",
        "        outs=['bin_a.out', 'bin_b.out'],",
        "        cmd=':',",
        "        output_to_bindir=1)",
        "genrule(name='genfiles', ",
        "        outs=['genfiles_a.out', 'genfiles_b.out'],",
        "        cmd=':',",
        "        output_to_bindir=0)");

    assertThat(getFileConfiguredTarget("//x:bin_a.out").getArtifact())
        .isEqualTo(getBinArtifact("bin_a.out", "//x:bin"));
    assertThat(getFileConfiguredTarget("//x:bin_b.out").getArtifact())
        .isEqualTo(getBinArtifact("bin_b.out", "//x:bin"));
    assertThat(getFileConfiguredTarget("//x:genfiles_a.out").getArtifact())
        .isEqualTo(getGenfilesArtifact("genfiles_a.out", "//x:genfiles"));
    assertThat(getFileConfiguredTarget("//x:genfiles_b.out").getArtifact())
        .isEqualTo(getGenfilesArtifact("genfiles_b.out", "//x:genfiles"));
  }

  @Test
  public void testMultipleOutsPreservesOrdering() throws Exception {
    scratch.file(
        "multiple/outs/BUILD",
        "genrule(name='test', ",
        "        outs=['file1.out', 'file2.out'],",
        "        cmd='touch $(OUTS)')");
    String regex =
        "touch b.{4}-out/.*/multiple/outs/file1.out "
            + "b.{4}-out/.*/multiple/outs/file2.out";
    assertThat(getCommand("//multiple/outs:test")).containsMatch(regex);
  }

  @Test
  public void testToolsAreHostConfiguration() throws Exception {
    scratch.file(
        "config/BUILD",
        "genrule(name='src', outs=['src.out'], cmd=':')",
        "genrule(name='tool', outs=['tool.out'], cmd=':')",
        "genrule(name='config', ",
        "        srcs=[':src'], tools=[':tool'], outs=['out'],",
        "        cmd='$(location :tool)')");

    Iterable<ConfiguredTarget> prereqs =
        Iterables.filter(
            Iterables.filter(
                getDirectPrerequisites(getConfiguredTarget("//config")),
                CC_CONFIGURED_TARGET_FILTER),
            JAVA_CONFIGURED_TARGET_FILTER);

    boolean foundSrc = false;
    boolean foundTool = false;
    boolean foundSetup = false;
    for (ConfiguredTarget prereq : prereqs) {
      String name = prereq.getLabel().getName();
      if (name.contains("cc-") || name.contains("jdk")) {
          // Ignore these, they are present due to the implied genrule dependency on crosstool and
          // JDK.
        continue;
      }
      switch (name) {
        case "src":
          assertConfigurationsEqual(getTargetConfiguration(), prereq.getConfiguration());
          foundSrc = true;
          break;
        case "tool":
          assertThat(getHostConfiguration().equalsOrIsSupersetOf(prereq.getConfiguration()))
              .isTrue();
          foundTool = true;
          break;
        case GENRULE_SETUP_PATH:
          assertThat(prereq.getConfiguration()).isNull();
          foundSetup = true;
          break;
        default:
          fail("unexpected prerequisite " + prereq + " (name: " + name + ")");
      }
    }

    assertThat(foundSrc).isTrue();
    assertThat(foundTool).isTrue();
    assertThat(foundSetup).isTrue();
  }

  @Test
  public void testLabelsContainingAtDAreExpanded() throws Exception {
    scratch.file(
        "puck/BUILD",
        "genrule(name='gen', ",
        "        tools=['puck'],",
        "        outs=['out'],",
        "        cmd='echo $(@D)')");
    String regex = "echo b.{4}-out/.*/puck";
    assertThat(getCommand("//puck:gen")).containsMatch(regex);
  }

  @Test
  public void testGetExecutable() throws Exception {
    ConfiguredTarget turtle =
        scratchConfiguredTarget(
            "java/com/google/turtle",
            "turtle_bootstrap",
            "genrule(name = 'turtle_bootstrap',",
            "    srcs = ['Turtle.java'],",
            "    outs = ['turtle'],",
            "    executable = 1,",
            "    cmd = 'touch $(OUTS)')");
    assertThat(getExecutable(turtle).getExecPath().getBaseName()).isEqualTo("turtle");
  }

  @Test
  public void testGetExecutableForNonExecutableOut() throws Exception {
    ConfiguredTarget turtle =
        scratchConfiguredTarget(
            "java/com/google/turtle",
            "turtle_bootstrap",
            "genrule(name = 'turtle_bootstrap',",
            "    srcs = ['Turtle.java'],",
            "    outs = ['debugdata.txt'],",
            "    cmd = 'touch $(OUTS)')");
    assertThat(getExecutable(turtle)).isNull();
  }

  @Test
  public void testGetExecutableForMultipleOuts() throws Exception {
    ConfiguredTarget turtle =
        scratchConfiguredTarget(
            "java/com/google/turtle",
            "turtle_bootstrap",
            "genrule(name = 'turtle_bootstrap',",
            "    srcs = ['Turtle.java'],",
            "    outs = ['turtle', 'debugdata.txt'],",
            "    cmd = 'touch $(OUTS)')");
    assertThat(getExecutable(turtle)).isNull();
  }

  @Test
  public void testGetExecutableFailsForMultipleOutputs() throws Exception {
    // Multiple output files are invalid when executable=1.
    checkError(
        "bad",
        "bad",
        "in executable attribute of genrule rule //bad:bad: "
            + "if genrules produce executables, they are allowed only one output. "
            + "If you need the executable=1 argument, then you should split this genrule into "
            + "genrules producing single outputs",
        "genrule(name = 'bad',",
        "        outs = [ 'bad_out1', 'bad_out2' ],",
        "        executable = 1,",
        "        cmd = 'touch $(OUTS)')");
  }

  @Test
  public void testEmptyOutsError() throws Exception {
    checkError(
        "x",
        "x",
        "Genrules without outputs don't make sense",
        "genrule(name = 'x', outs = [], cmd='echo')");
  }

  @Test
  public void testGenruleSetup() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'foo_sh',",
        "        outs = [ 'foo.sh' ],", // Shell script files are known to be executable.
        "        cmd = 'touch $@')");

    assertThat(getCommand("//foo:foo_sh")).contains(GENRULE_SETUP_PATH);
  }

  private void createStampingTargets() throws Exception {
    scratch.file(
        "u/BUILD",
        "genrule(name='foo_stamp', srcs=[], outs=['uu'], stamp=1, cmd='')",
        "genrule(name='foo_nostamp', srcs=[], outs=['vv'], stamp=0, cmd='')",
        "genrule(name='foo_default', srcs=[], outs=['xx'], cmd='')");
  }

  private void assertStamped(String target) throws Exception {
    assertStamped(getConfiguredTarget(target));
  }

  private void assertNotStamped(String target) throws Exception {
    assertNotStamped(getConfiguredTarget(target));
  }

  private void assertStamped(ConfiguredTarget target) throws Exception {
    Artifact out = Iterables.getFirst(getFilesToBuild(target), null);
    List<String> inputs = ActionsTestUtil.baseArtifactNames(getGeneratingAction(out).getInputs());
    assertThat(inputs).containsAllOf("build-info.txt", "build-changelist.txt");
  }

  private void assertNotStamped(ConfiguredTarget target) throws Exception {
    Artifact out = Iterables.getFirst(getFilesToBuild(target), null);
    List<String> inputs = ActionsTestUtil.baseArtifactNames(getGeneratingAction(out).getInputs());
    assertThat(inputs).doesNotContain("build-info.txt");
    assertThat(inputs).doesNotContain("build-changelist.txt");
  }

  @Test
  public void testStampingWithNoStamp() throws Exception {
    useConfiguration("--nostamp");
    createStampingTargets();
    assertStamped("//u:foo_stamp");
    assertStamped(getHostConfiguredTarget("//u:foo_stamp"));
    assertNotStamped("//u:foo_nostamp");
    assertNotStamped(getHostConfiguredTarget("//u:foo_nostamp"));
    assertNotStamped("//u:foo_default");
  }

  @Test
  public void testStampingWithStamp() throws Exception {
    useConfiguration("--stamp");
    createStampingTargets();
    assertStamped("//u:foo_stamp");
    assertStamped(getHostConfiguredTarget("//u:foo_stamp"));
    //assertStamped("//u:foo_nostamp");
    assertNotStamped(getHostConfiguredTarget("//u:foo_nostamp"));
    assertNotStamped("//u:foo_default");
  }

  @Test
  public void testRequiresDarwin() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name='darwin', srcs=[], outs=['macout'], cmd='', tags=['requires-darwin'])");

    SpawnAction action = getSpawnAction("//foo:darwin");
    assertThat(action.getExecutionInfo().keySet()).contains("requires-darwin");
    // requires-darwin causes /bin/bash to be hard-coded, see CommandHelper.shellPath().
    assertThat(action.getCommandFilename())
        .isEqualTo("/bin/bash");
  }

  @Test
  public void testJarError() throws Exception {
    checkError(
        "foo",
        "grj",
        "in cmd attribute of genrule rule //foo:grj: $(JAR) not defined",
        "genrule(name='grj',"
            + "      srcs = [],"
            + "      outs=['grj'],"
            + "      cmd='$(JAR) foo bar')");
  }

  /** Regression test for b/15589451. */
  @Test
  public void testDuplicateLocalFlags() throws Exception {
    scratch.file(
        "foo/BUILD",
        "genrule(name='g',"
            + "      srcs = [],"
            + "      outs = ['grj'],"
            + "      cmd ='echo g',"
            + "      local = 1,"
            + "      tags = ['local'])");
    getConfiguredTarget("//foo:g");
    assertNoEvents();
  }
}
