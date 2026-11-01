'use strict';

// Intake checks for issues and pull requests. checkIssue and checkPullRequest decide whether a submission followed its
// template, as plain functions of text; run closes a submission that did not, with a comment that says what is missing,
// and reopens it when an edit fixes that. Titles and bodies are untrusted input: they are only ever read as data here, and
// nothing in a comment repeats them.

const LABEL = 'invalid';
const LABEL_COLOR = 'e4e669';
const LABEL_DESCRIPTION = 'Did not follow the issue or pull request template';
const EXEMPT_ASSOCIATIONS = new Set(['OWNER', 'MEMBER', 'COLLABORATOR']);
const NO_RESPONSE = '_No response_';

// The sections of each issue form, by the labels the forms in .github/ISSUE_TEMPLATE use.
const ISSUE_FORMS = {
  bug: {
    marker: '问题描述',
    required: ['问题描述', '复现步骤', '预期行为', '抖音版本', '模块版本', '进入方式', '系统与设备', '日志'],
    // One-line fields: a short answer such as a version number or a mode is an answer.
    short: ['抖音版本', '模块版本', '进入方式', '系统与设备'],
    versions: ['抖音版本', '模块版本'],
    checklist: '确认事项',
  },
  feature: {
    marker: '功能描述',
    required: ['功能描述', '使用场景'],
    short: [],
    versions: [],
    checklist: '确认事项',
  },
};

// The sections of the pull request template. Checklist items marked （必须） have to be ticked.
const PULL_REQUEST = {
  required: ['变更说明', '验证'],
  checklist: '检查清单',
  mustMark: /[（(]必须[）)]/,
  targetBranch: 'dev',
};

// The header rule of tools/check_commit_msg.py, for a pull request title.
const COMMIT_TYPES = ['feat', 'fix', 'refactor', 'perf', 'test', 'docs', 'build', 'ci', 'chore', 'revert'];
const TITLE = new RegExp(`^(${COMMIT_TYPES.join('|')})(\\([a-z0-9][a-z0-9._/-]*\\))?(!)?: (\\S.*)$`);
const MAX_TITLE = 100;

// "latest" and the like are not versions; a version has a digit.
const VAGUE_VERSION = /^\s*(最新(版|版本|的)?|新版|latest|newest|current)\s*$/i;

function stripComments(text) {
  return text.replace(/<!--[\s\S]*?-->/g, '');
}

// The sections of a markdown body under headings of the given depth, by heading text. A fenced block around a value, as the
// issue forms put around text that is rendered as code, is taken off.
function sections(body, depth) {
  const result = new Map();
  const heading = new RegExp(`^#{${depth}}[ \\t]+(.+?)[ \\t]*$`);
  let name = null;
  let lines = [];
  const finish = () => {
    if (name !== null && !result.has(name)) result.set(name, lines.join('\n'));
  };
  for (const line of stripComments(String(body || '').replace(/\r\n?/g, '\n')).split('\n')) {
    const match = heading.exec(line);
    if (match) {
      finish();
      name = match[1];
      lines = [];
    } else {
      lines.push(line);
    }
  }
  finish();
  for (const [key, value] of result) {
    result.set(key, value.replace(/^\s*```[\w-]*\s*$/gm, '').trim());
  }
  return result;
}

// Whether a value says something: it is not the form's "no response" and holds at least four letters, digits or
// characters of a script.
function meaningful(value) {
  if (value === undefined) return false;
  const text = value.replace(NO_RESPONSE, '').trim();
  return (text.match(/[\p{L}\p{N}]/gu) || []).length >= 4;
}

// Whether a one-line field was answered at all.
function filled(value) {
  return value !== undefined && value.replace(NO_RESPONSE, '').trim().length > 0;
}

function checklist(value) {
  return [...String(value || '').matchAll(/^\s*[-*]\s+\[([ xX])\]\s+(.*)$/gm)].map((match) => ({
    checked: match[1] !== ' ',
    text: match[2],
  }));
}

function checkIssue({ body }) {
  const found = sections(body, 3);
  const kind = Object.keys(ISSUE_FORMS).find((name) => found.has(ISSUE_FORMS[name].marker));
  if (!kind) {
    return { kind: null, problems: ['没有使用议题模板：请用「New issue」里的表单提交，不要删掉模板里的标题'] };
  }
  const form = ISSUE_FORMS[kind];
  const problems = [];
  for (const name of form.required) {
    if (!found.has(name)) {
      problems.push(`缺少「${name}」一节，不要删掉模板里的标题`);
    } else if (!(form.short.includes(name) ? filled : meaningful)(found.get(name))) {
      problems.push(`「${name}」没有填写`);
    }
  }
  for (const name of form.versions) {
    const value = found.get(name);
    if (filled(value) && (VAGUE_VERSION.test(value) || !/\d/.test(value))) {
      problems.push(`「${name}」要写具体的版本号，「最新版」不算`);
    }
  }
  if (!found.has(form.checklist)) {
    problems.push(`缺少「${form.checklist}」一节，不要删掉模板里的标题`);
  } else {
    const items = checklist(found.get(form.checklist));
    if (items.length === 0 || items.some((item) => !item.checked)) {
      problems.push(`「${form.checklist}」里的每一项都要勾选`);
    }
  }
  return { kind, problems };
}

function checkPullRequest({ title, body, base }) {
  const problems = [];
  const header = String(title || '');
  const match = TITLE.exec(header);
  if (!match) {
    problems.push(`标题要写成 \`<type>(<scope>): <description>\`，type 取 ${COMMIT_TYPES.join('、')}`);
  } else {
    if (header.endsWith('.')) problems.push('标题末尾不要加句号');
    if (/[A-Z]/.test(match[4][0])) problems.push('标题的描述要以小写字母开头');
  }
  if (header.length > MAX_TITLE) problems.push(`标题有 ${header.length} 个字符，上限是 ${MAX_TITLE}`);
  if ([...header].some((letter) => /\p{L}/u.test(letter) && !/\p{Script=Latin}/u.test(letter))) {
    problems.push('标题要用英文，和提交信息一致');
  }
  if (base !== PULL_REQUEST.targetBranch) {
    problems.push(`目标分支必须是 ${PULL_REQUEST.targetBranch}`);
  }
  const found = sections(body, 2);
  if (!PULL_REQUEST.required.some((name) => found.has(name)) && !found.has(PULL_REQUEST.checklist)) {
    problems.push('没有使用 PR 模板，不要删掉模板里的标题');
    return { problems };
  }
  for (const name of PULL_REQUEST.required) {
    if (!found.has(name)) {
      problems.push(`缺少「${name}」一节，不要删掉模板里的标题`);
    } else if (!meaningful(found.get(name))) {
      problems.push(`「${name}」没有填写`);
    }
  }
  if (!found.has(PULL_REQUEST.checklist)) {
    problems.push(`缺少「${PULL_REQUEST.checklist}」一节，不要删掉模板里的标题`);
  } else {
    const required = checklist(found.get(PULL_REQUEST.checklist)).filter((item) => PULL_REQUEST.mustMark.test(item.text));
    if (required.length === 0 || required.some((item) => !item.checked)) {
      problems.push(`「${PULL_REQUEST.checklist}」里标了（必须）的每一项都要勾选`);
    }
  }
  return { problems };
}

// The owner, members and collaborators, and bots such as dependabot, are not held to the templates.
function exempt({ association, userType, login }) {
  return EXEMPT_ASSOCIATIONS.has(association) || userType === 'Bot' || String(login || '').endsWith('[bot]');
}

function closingComment(isPullRequest, problems) {
  const subject = isPullRequest ? 'PR' : '议题';
  return [
    `这个${subject}没有按要求填写，已先关闭。请编辑它，补全下面几项；补全后会自动重新打开：`,
    '',
    ...problems.map((problem) => `- ${problem}`),
    '',
    isPullRequest
      ? '模板在 `.github/PULL_REQUEST_TEMPLATE.md`，提交信息的规则见仓库的 `AGENTS.md`。'
      : '提问前请先看 README 的「出问题时」。',
  ].join('\n');
}

async function ensureLabel(github, owner, repo) {
  try {
    await github.rest.issues.createLabel({ owner, repo, name: LABEL, color: LABEL_COLOR, description: LABEL_DESCRIPTION });
  } catch (error) {
    if (error.status !== 422) throw error;
  }
}

// Checks the issue or pull request of the event. Returns what was done: 'exempt', 'ok', 'closed', 'reopened' or 'left'.
async function run({ github, context, pullRequest }) {
  const { owner, repo } = context.repo;
  const item = pullRequest ? context.payload.pull_request : context.payload.issue;
  if (exempt({ association: item.author_association, userType: item.user && item.user.type, login: item.user && item.user.login })) {
    return 'exempt';
  }
  const labels = (item.labels || []).map((label) => label.name);
  const { problems } = pullRequest
    ? checkPullRequest({ title: item.title, body: item.body, base: item.base && item.base.ref })
    : checkIssue({ body: item.body });
  const number = item.number;
  const setState = (state) => (pullRequest
    ? github.rest.pulls.update({ owner, repo, pull_number: number, state })
    : github.rest.issues.update({ owner, repo, issue_number: number, state, ...(state === 'closed' ? { state_reason: 'not_planned' } : {}) }));

  if (problems.length === 0) {
    if (item.state === 'closed' && labels.includes(LABEL) && !item.merged) {
      await setState('open');
      await github.rest.issues.removeLabel({ owner, repo, issue_number: number, name: LABEL });
      await github.rest.issues.createComment({ owner, repo, issue_number: number, body: '已重新检查，必填的内容都补全了，已重新打开。' });
      return 'reopened';
    }
    return 'ok';
  }
  if (item.state !== 'open') return 'left';
  await ensureLabel(github, owner, repo);
  await github.rest.issues.addLabels({ owner, repo, issue_number: number, labels: [LABEL] });
  await github.rest.issues.createComment({ owner, repo, issue_number: number, body: closingComment(Boolean(pullRequest), problems) });
  await setState('closed');
  return 'closed';
}

module.exports = {
  LABEL,
  ISSUE_FORMS,
  PULL_REQUEST,
  COMMIT_TYPES,
  sections,
  meaningful,
  filled,
  checklist,
  checkIssue,
  checkPullRequest,
  exempt,
  closingComment,
  run,
};
