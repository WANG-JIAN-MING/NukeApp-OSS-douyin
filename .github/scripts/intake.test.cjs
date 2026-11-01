'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const intake = require('./intake.cjs');

const root = path.join(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

// The fields of an issue form: type, id, label, whether it is required, and the options of dropdowns and checkboxes.
function fieldsOf(yaml) {
  const fields = [];
  let current = null;
  let inOptions = false;
  for (const line of yaml.split('\n')) {
    let match = /^ {2}- type: (\w+)/.exec(line);
    if (match) {
      current = { type: match[1], options: [], required: false };
      fields.push(current);
      inOptions = false;
    } else if (current) {
      if ((match = /^ {4}id: (.+)$/.exec(line))) current.id = match[1];
      else if ((match = /^ {6}label: (.+)$/.exec(line))) current.label = match[1].replace(/^"|"$/g, '');
      else if (/^ {6}options:/.test(line)) inOptions = true;
      else if (inOptions && (match = /^ {8}- label: (.+)$/.exec(line))) current.options.push({ text: match[1], required: false });
      else if (inOptions && /^ {10}required: true/.test(line)) current.options[current.options.length - 1].required = true;
      else if (inOptions && current.type === 'dropdown' && (match = /^ {8}- (.+)$/.exec(line))) current.options.push({ text: match[1] });
      else if (/^ {6}required: true/.test(line)) current.required = true;
    }
  }
  return fields;
}

// A body the way GitHub writes it for a submitted form, with `values` by label and every box ticked unless told otherwise.
function bodyOf(yaml, values = {}, { tick = true } = {}) {
  const parts = [];
  for (const field of fieldsOf(yaml)) {
    if (field.type === 'markdown') continue;
    let value = values[field.label];
    if (field.type === 'checkboxes') {
      value = field.options.map((option) => `- [${tick ? 'X' : ' '}] ${option.text}`).join('\n');
    } else if (value === undefined) {
      value = field.required ? `这是「${field.label}」的内容，写得足够具体` : '_No response_';
      if (field.id === 'douyin-version' || field.id === 'module-version') value = field.required ? '40.7.0' : '_No response_';
      if (field.type === 'dropdown') value = field.options[0].text;
    }
    if (field.id === 'logs') value = '```text\n' + value + '\n```';
    parts.push(`### ${field.label}\n\n${value}`);
  }
  return parts.join('\n\n');
}

const bug = read('ISSUE_TEMPLATE/bug_report.yml');
const feature = read('ISSUE_TEMPLATE/feature_request.yml');
const pullRequestTemplate = read('PULL_REQUEST_TEMPLATE.md');
const withoutComments = (text) => text.replace(/<!--[\s\S]*?-->/g, '');

// ---- the templates and the rules stay in step

test('the bug form asks for exactly the sections the rules require, and every box must be ticked', () => {
  const fields = fieldsOf(bug).filter((field) => field.type !== 'markdown');
  const asked = fields.filter((field) => field.required && field.type !== 'checkboxes').map((field) => field.label);
  assert.deepEqual(asked.sort(), [...intake.ISSUE_FORMS.bug.required].sort());
  const boxes = fields.find((field) => field.type === 'checkboxes');
  assert.equal(boxes.label, intake.ISSUE_FORMS.bug.checklist);
  assert.ok(boxes.options.length > 0 && boxes.options.every((option) => option.required));
  assert.ok(fields.some((field) => field.label === intake.ISSUE_FORMS.bug.marker));
});

test('the feature form asks for exactly the sections the rules require', () => {
  const fields = fieldsOf(feature).filter((field) => field.type !== 'markdown');
  const asked = fields.filter((field) => field.required && field.type !== 'checkboxes').map((field) => field.label);
  assert.deepEqual(asked.sort(), [...intake.ISSUE_FORMS.feature.required].sort());
  const boxes = fields.find((field) => field.type === 'checkboxes');
  assert.equal(boxes.label, intake.ISSUE_FORMS.feature.checklist);
  assert.ok(boxes.options.every((option) => option.required));
});

test('one-line fields are inputs or dropdowns and version fields exist in the form', () => {
  const fields = fieldsOf(bug);
  for (const name of intake.ISSUE_FORMS.bug.short) {
    assert.ok(['input', 'dropdown'].includes(fields.find((field) => field.label === name).type), name);
  }
  for (const name of intake.ISSUE_FORMS.bug.versions) {
    assert.equal(fields.find((field) => field.label === name).type, 'input');
  }
});

test('the pull request template has the sections and the required items the rules look for', () => {
  const found = intake.sections(pullRequestTemplate, 2);
  for (const name of [...intake.PULL_REQUEST.required, intake.PULL_REQUEST.checklist]) assert.ok(found.has(name), name);
  const items = intake.checklist(found.get(intake.PULL_REQUEST.checklist));
  assert.ok(items.filter((item) => intake.PULL_REQUEST.mustMark.test(item.text)).length >= 4);
  assert.ok(items.some((item) => !intake.PULL_REQUEST.mustMark.test(item.text)), 'there is an optional item too');
  assert.ok(items.every((item) => !item.checked), 'the template ships unticked');
});

test('blank issues are off, so the forms are the only way in', () => {
  assert.match(read('ISSUE_TEMPLATE/config.yml'), /blank_issues_enabled: false/);
});

// ---- issues

test('a filled-in bug form passes', () => {
  assert.deepEqual(intake.checkIssue({ body: bodyOf(bug) }).problems, []);
  assert.equal(intake.checkIssue({ body: bodyOf(bug) }).kind, 'bug');
});

test('a filled-in feature form passes', () => {
  const result = intake.checkIssue({ body: bodyOf(feature) });
  assert.deepEqual(result.problems, []);
  assert.equal(result.kind, 'feature');
});

test('a free-form body is not a form', () => {
  const result = intake.checkIssue({ body: '用不了，怎么办？？？' });
  assert.equal(result.kind, null);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /没有使用议题模板/);
  assert.equal(intake.checkIssue({ body: '' }).kind, null);
  assert.equal(intake.checkIssue({ body: null }).kind, null);
});

test('a removed section is named', () => {
  const body = bodyOf(bug).replace(/### 复现步骤\n\n[^\n]*\n\n/, '');
  assert.deepEqual(intake.checkIssue({ body }).problems, ['缺少「复现步骤」一节，不要删掉模板里的标题']);
});

test('an empty or unanswered section is named', () => {
  for (const empty of ['_No response_', '', '   ', '。', '无']) {
    const body = bodyOf(bug, { 复现步骤: empty });
    assert.deepEqual(intake.checkIssue({ body }).problems, ['「复现步骤」没有填写'], JSON.stringify(empty));
  }
});

test('the log section may hold only a fence and still counts as empty', () => {
  const body = bodyOf(bug, { 日志: '' });
  assert.deepEqual(intake.checkIssue({ body }).problems, ['「日志」没有填写']);
});

test('a log wrapped in a code fence counts as filled', () => {
  const body = bodyOf(bug, { 日志: 'E NukeApp-OSS/Zygisk: zygisk_started process=com.ss.android.ugc.aweme' });
  assert.deepEqual(intake.checkIssue({ body }).problems, []);
});

test('short one-line answers are answers', () => {
  const body = bodyOf(bug, { 抖音版本: '40.7', 模块版本: '1.0', 进入方式: 'Zygisk', 系统与设备: '14' });
  assert.deepEqual(intake.checkIssue({ body }).problems, []);
});

test('a version has to be a version', () => {
  for (const vague of ['最新版', '最新', 'latest', ' Latest ', '最新版本', '新版', 'newest', '不知道']) {
    const body = bodyOf(bug, { 抖音版本: vague });
    assert.deepEqual(intake.checkIssue({ body }).problems, ['「抖音版本」要写具体的版本号，「最新版」不算'], vague);
  }
  const both = bodyOf(bug, { 抖音版本: '最新版', 模块版本: 'latest' });
  assert.equal(intake.checkIssue({ body: both }).problems.length, 2);
});

test('a version that is only a commit hash with digits is accepted', () => {
  assert.deepEqual(intake.checkIssue({ body: bodyOf(bug, { 模块版本: '9f3c2ab' }) }).problems, []);
});

test('every box has to be ticked', () => {
  const body = bodyOf(bug, {}, { tick: false });
  assert.deepEqual(intake.checkIssue({ body }).problems, ['「确认事项」里的每一项都要勾选']);
  const one = bodyOf(bug).replace('- [X]', '- [ ]');
  assert.deepEqual(intake.checkIssue({ body: one }).problems, ['「确认事项」里的每一项都要勾选']);
});

test('a lowercase x ticks a box and an empty list does not', () => {
  assert.deepEqual(intake.checkIssue({ body: bodyOf(bug).replace(/\[X\]/g, '[x]') }).problems, []);
  const noBoxes = bodyOf(bug).replace(/- \[X\] .*\n?/g, '');
  assert.deepEqual(intake.checkIssue({ body: noBoxes }).problems, ['「确认事项」里的每一项都要勾选']);
});

test('a missing checklist section is named', () => {
  const body = bodyOf(bug).replace(/### 确认事项[\s\S]*$/, '');
  assert.deepEqual(intake.checkIssue({ body }).problems, ['缺少「确认事项」一节，不要删掉模板里的标题']);
});

test('several problems are all listed', () => {
  const body = bodyOf(bug, { 复现步骤: '', 抖音版本: '最新版' }, { tick: false });
  assert.equal(intake.checkIssue({ body }).problems.length, 3);
});

test('windows line endings and a heading written twice do not fool the check', () => {
  assert.deepEqual(intake.checkIssue({ body: bodyOf(bug).replace(/\n/g, '\r\n') }).problems, []);
  // The first heading of a name is the one that counts: a filled copy added below an empty one does not help.
  const trick = bodyOf(bug, { 复现步骤: '' }) + '\n\n### 复现步骤\n\n这里补了一段很长的内容';
  assert.deepEqual(intake.checkIssue({ body: trick }).problems, ['「复现步骤」没有填写']);
});

test('text hidden in a comment does not count as an answer', () => {
  const body = bodyOf(bug, { 复现步骤: '<!-- 这是一段写在注释里的很长的内容，看不见 -->' });
  assert.deepEqual(intake.checkIssue({ body }).problems, ['「复现步骤」没有填写']);
});

// ---- pull requests

const goodBody = [
  '## 变更说明\n\n修了一处 Zygisk 入口读取库目录的错误。',
  '## 关联议题\n\n无',
  '## 验证\n\n在 Android 16 上跑过 ./gradlew verify，全部通过。',
  '## 检查清单\n\n' + intake.checklist(intake.sections(pullRequestTemplate, 2).get('检查清单'))
    .map((item) => `- [${intake.PULL_REQUEST.mustMark.test(item.text) ? 'x' : ' '}] ${item.text}`).join('\n'),
].join('\n\n');

test('a filled-in pull request passes, with the optional item left unticked', () => {
  assert.deepEqual(intake.checkPullRequest({ title: 'fix(zygisk): read the library directory', body: goodBody, base: 'dev' }).problems, []);
});

test('titles follow the commit header rule', () => {
  const problemsFor = (title) => intake.checkPullRequest({ title, body: goodBody, base: 'dev' }).problems;
  for (const good of ['feat: add a thing', 'fix(zygisk): x', 'refactor(rust)!: drop an ABI', 'docs(license): a b', 'chore(deps-dev): bump x', 'revert: undo it']) {
    assert.deepEqual(problemsFor(good), [], good);
  }
  for (const bad of ['Fix the bug', 'fix:no space', 'feature: x', 'Fix(zygisk): x', 'fix(Zygisk): x', 'fix(zygisk): Add', 'fix(zygisk): x.', 'fix(zygisk):  ', '更新了东西', '']) {
    assert.ok(problemsFor(bad).length > 0, JSON.stringify(bad));
  }
  assert.ok(problemsFor('fix(zygisk): ' + 'x'.repeat(100)).some((problem) => problem.includes('上限是 100')));
  assert.ok(problemsFor('fix(zygisk): 修复库目录').some((problem) => problem.includes('英文')));
});

test('the target branch must be dev', () => {
  for (const base of ['master', 'main', 'feat/x', undefined]) {
    assert.deepEqual(intake.checkPullRequest({ title: 'fix: x y', body: goodBody, base }).problems, ['目标分支必须是 dev'], String(base));
  }
});

test('the template left as it is does not pass', () => {
  const problems = intake.checkPullRequest({ title: 'fix: x y', body: pullRequestTemplate, base: 'dev' }).problems;
  assert.deepEqual(problems, ['「变更说明」没有填写', '「验证」没有填写', '「检查清单」里标了（必须）的每一项都要勾选']);
});

test('a pull request without the template is named once', () => {
  const problems = intake.checkPullRequest({ title: 'fix: x y', body: '改了点东西', base: 'dev' }).problems;
  assert.deepEqual(problems, ['没有使用 PR 模板，不要删掉模板里的标题']);
  assert.deepEqual(intake.checkPullRequest({ title: 'fix: x y', body: null, base: 'dev' }).problems, ['没有使用 PR 模板，不要删掉模板里的标题']);
});

test('one required item left unticked is named', () => {
  const body = goodBody.replace('- [x]', '- [ ]');
  assert.deepEqual(intake.checkPullRequest({ title: 'fix: x y', body, base: 'dev' }).problems, ['「检查清单」里标了（必须）的每一项都要勾选']);
});

// ---- who is held to the templates

test('maintainers and bots are exempt and contributors are not', () => {
  for (const association of ['OWNER', 'MEMBER', 'COLLABORATOR']) assert.ok(intake.exempt({ association }));
  assert.ok(intake.exempt({ association: 'NONE', userType: 'Bot' }));
  assert.ok(intake.exempt({ association: 'NONE', login: 'dependabot[bot]' }));
  for (const association of ['NONE', 'CONTRIBUTOR', 'FIRST_TIME_CONTRIBUTOR', 'FIRST_TIMER', 'MANNEQUIN', undefined]) {
    assert.ok(!intake.exempt({ association, userType: 'User', login: 'someone' }), String(association));
  }
});

// ---- what run does

function fake({ issue, pullRequest, labelExists = false }) {
  const calls = [];
  const record = (name) => async (args) => { calls.push([name, args]); return {}; };
  const github = {
    rest: {
      issues: {
        createLabel: async (args) => {
          calls.push(['createLabel', args]);
          if (labelExists) throw Object.assign(new Error('exists'), { status: 422 });
          return {};
        },
        addLabels: record('addLabels'),
        removeLabel: record('removeLabel'),
        createComment: record('createComment'),
        update: record('issues.update'),
      },
      pulls: { update: record('pulls.update') },
    },
  };
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { issue, pull_request: pullRequest } };
  return { github, context, calls };
}
const names = (calls) => calls.map(([name]) => name);
const issueOf = (overrides) => ({ number: 7, state: 'open', labels: [], author_association: 'NONE', user: { type: 'User', login: 'a' }, body: '随便写写', title: 't', ...overrides });
const pullOf = (overrides) => ({ number: 8, state: 'open', labels: [], author_association: 'NONE', user: { type: 'User', login: 'a' }, body: '随便写写', title: 'fix: x y', base: { ref: 'dev' }, merged: false, ...overrides });

test('an invalid issue is labelled, explained and closed as not planned', async () => {
  const { github, context, calls } = fake({ issue: issueOf() });
  assert.equal(await intake.run({ github, context }), 'closed');
  assert.deepEqual(names(calls), ['createLabel', 'addLabels', 'createComment', 'issues.update']);
  const comment = calls.find(([name]) => name === 'createComment')[1];
  assert.match(comment.body, /议题没有按要求填写/);
  assert.match(comment.body, /没有使用议题模板/);
  assert.deepEqual(calls.find(([name]) => name === 'issues.update')[1], { owner: 'o', repo: 'r', issue_number: 7, state: 'closed', state_reason: 'not_planned' });
});

test('the label already existing is not an error', async () => {
  const { github, context } = fake({ issue: issueOf(), labelExists: true });
  assert.equal(await intake.run({ github, context }), 'closed');
});

test('an error other than the label existing is raised', async () => {
  const { github, context } = fake({ issue: issueOf() });
  github.rest.issues.createLabel = async () => { throw Object.assign(new Error('forbidden'), { status: 403 }); };
  await assert.rejects(intake.run({ github, context }), /forbidden/);
});

test('a valid issue is left alone', async () => {
  const { github, context, calls } = fake({ issue: issueOf({ body: bodyOf(bug) }) });
  assert.equal(await intake.run({ github, context }), 'ok');
  assert.deepEqual(calls, []);
});

test('an exempt author is left alone even without a form', async () => {
  for (const overrides of [{ author_association: 'OWNER' }, { user: { type: 'Bot', login: 'dependabot[bot]' } }]) {
    const { github, context, calls } = fake({ issue: issueOf(overrides) });
    assert.equal(await intake.run({ github, context }), 'exempt');
    assert.deepEqual(calls, []);
  }
});

test('an issue closed for being invalid is reopened when an edit fixes it', async () => {
  const { github, context, calls } = fake({ issue: issueOf({ state: 'closed', labels: [{ name: 'invalid' }], body: bodyOf(bug) }) });
  assert.equal(await intake.run({ github, context }), 'reopened');
  assert.deepEqual(names(calls), ['issues.update', 'removeLabel', 'createComment']);
  assert.equal(calls[0][1].state, 'open');
  assert.equal(calls[1][1].name, 'invalid');
});

test('an issue that stays invalid after it was closed is not commented on again', async () => {
  const { github, context, calls } = fake({ issue: issueOf({ state: 'closed', labels: [{ name: 'invalid' }] }) });
  assert.equal(await intake.run({ github, context }), 'left');
  assert.deepEqual(calls, []);
});

test('an issue a maintainer closed for another reason is not reopened', async () => {
  const { github, context, calls } = fake({ issue: issueOf({ state: 'closed', labels: [{ name: 'wontfix' }], body: bodyOf(bug) }) });
  assert.equal(await intake.run({ github, context }), 'ok');
  assert.deepEqual(calls, []);
});

test('an invalid pull request is closed through the pulls api with its own comment', async () => {
  const { github, context, calls } = fake({ pullRequest: pullOf() });
  assert.equal(await intake.run({ github, context, pullRequest: true }), 'closed');
  assert.deepEqual(names(calls), ['createLabel', 'addLabels', 'createComment', 'pulls.update']);
  assert.deepEqual(calls[3][1], { owner: 'o', repo: 'r', pull_number: 8, state: 'closed' });
  assert.match(calls[2][1].body, /PR没有按要求填写/);
});

test('a pull request is reopened when it is fixed, unless it was merged', async () => {
  const fixed = pullOf({ state: 'closed', labels: [{ name: 'invalid' }], body: goodBody });
  const { github, context, calls } = fake({ pullRequest: fixed });
  assert.equal(await intake.run({ github, context, pullRequest: true }), 'reopened');
  assert.deepEqual(calls[0], ['pulls.update', { owner: 'o', repo: 'r', pull_number: 8, state: 'open' }]);
  const merged = fake({ pullRequest: { ...fixed, merged: true } });
  assert.equal(await intake.run({ github: merged.github, context: merged.context, pullRequest: true }), 'ok');
  assert.deepEqual(merged.calls, []);
});

test('a comment never repeats what the author wrote', async () => {
  const hostile = '@everyone <script>alert(1)</script> https://evil.example/x';
  const { github, context, calls } = fake({ issue: issueOf({ body: hostile, title: hostile }) });
  await intake.run({ github, context });
  const comment = calls.find(([name]) => name === 'createComment')[1].body;
  for (const piece of ['@everyone', 'script', 'evil.example']) assert.ok(!comment.includes(piece), piece);
});
