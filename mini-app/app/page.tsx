"use client";

import {
  ArrowLeft,
  Bot,
  Check,
  CheckCheck,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Clock3,
  History,
  Inbox,
  LoaderCircle,
  Megaphone,
  MessageSquareText,
  MoreHorizontal,
  PencilLine,
  Plus,
  RefreshCw,
  Search,
  Send,
  Sparkles,
  Trash2,
  UserRound,
  UsersRound,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

type Tab = "answers" | "students" | "broadcasts";
type StudentFilter = "all" | "active" | "attention";
type BroadcastView = "compose" | "history";
type DraftStatus = "pending" | "sent" | "rejected";

type Student = {
  id: string;
  name: string;
  initials: string;
  group: string;
  course: string;
  lastSeen: string;
  status: "active" | "attention";
  color: string;
};

type Draft = {
  id: string;
  studentId: string;
  student: string;
  initials: string;
  question: string;
  answer: string;
  subject: string;
  receivedAt: string;
  waitTime: string;
  status: DraftStatus;
  confidence: number;
  color: string;
};

type Campaign = {
  id: string;
  title: string;
  date: string;
  recipients: number;
  sent: number;
  failed: number;
  status: "sent" | "sending" | "draft";
};

declare global {
  interface Window {
    Telegram?: {
      WebApp?: {
        initData: string;
        colorScheme: "light" | "dark";
        ready: () => void;
        expand: () => void;
        HapticFeedback?: {
          impactOccurred: (style: "light" | "medium" | "heavy") => void;
          notificationOccurred: (
            type: "error" | "success" | "warning",
          ) => void;
        };
      };
    };
  }
}

const studentsSeed: Student[] = [
  {
    id: "s1",
    name: "Алина Соколова",
    initials: "АС",
    group: "ОГЭ 2027",
    course: "Математика",
    lastSeen: "5 мин назад",
    status: "active",
    color: "#e9f3ff",
  },
  {
    id: "s2",
    name: "Максим Орлов",
    initials: "МО",
    group: "ЕГЭ Профиль",
    course: "Математика",
    lastSeen: "18 мин назад",
    status: "attention",
    color: "#fff0dc",
  },
  {
    id: "s3",
    name: "София Ким",
    initials: "СК",
    group: "ЕГЭ Профиль",
    course: "Математика",
    lastSeen: "сегодня, 14:32",
    status: "active",
    color: "#e7f7ee",
  },
  {
    id: "s4",
    name: "Даниил Волков",
    initials: "ДВ",
    group: "ОГЭ 2027",
    course: "Математика",
    lastSeen: "сегодня, 12:10",
    status: "active",
    color: "#f1edff",
  },
  {
    id: "s5",
    name: "Виктория Лебедева",
    initials: "ВЛ",
    group: "ЕГЭ База",
    course: "Математика",
    lastSeen: "вчера",
    status: "attention",
    color: "#ffecec",
  },
  {
    id: "s6",
    name: "Кирилл Новиков",
    initials: "КН",
    group: "ЕГЭ База",
    course: "Математика",
    lastSeen: "вчера",
    status: "active",
    color: "#eaf7f6",
  },
  {
    id: "s7",
    name: "Мария Федорова",
    initials: "МФ",
    group: "ОГЭ 2027",
    course: "Математика",
    lastSeen: "2 дня назад",
    status: "active",
    color: "#f7edfa",
  },
  {
    id: "s8",
    name: "Артем Мельников",
    initials: "АМ",
    group: "ЕГЭ Профиль",
    course: "Математика",
    lastSeen: "3 дня назад",
    status: "attention",
    color: "#fff3e7",
  },
];

const draftsSeed: Draft[] = [
  {
    id: "d1",
    studentId: "s2",
    student: "Максим Орлов",
    initials: "МО",
    question:
      "Не понимаю, почему при переносе слагаемого знак обязательно меняется. Можете объяснить еще раз?",
    answer:
      "Максим, знак меняется не из-за самого «переноса». Мы выполняем одно и то же действие с обеими частями уравнения. Например, в x + 3 = 7 вычитаем 3 слева и справа: x + 3 - 3 = 7 - 3. Слева остается x, справа получается 4. Запись x = 7 - 3 — просто короткая форма этого действия.",
    subject: "Линейные уравнения",
    receivedAt: "16:42",
    waitTime: "12 мин",
    status: "pending",
    confidence: 94,
    color: "#fff0dc",
  },
  {
    id: "d2",
    studentId: "s1",
    student: "Алина Соколова",
    initials: "АС",
    question: "Можно ли сдать домашку завтра утром? Сегодня не успеваю.",
    answer:
      "Алина, да, можешь отправить работу завтра до 10:00. Постарайся решить хотя бы первые пять заданий самостоятельно, а места, где возникли вопросы, отметь — разберем их вместе.",
    subject: "Домашняя работа",
    receivedAt: "16:38",
    waitTime: "16 мин",
    status: "pending",
    confidence: 88,
    color: "#e9f3ff",
  },
  {
    id: "d3",
    studentId: "s5",
    student: "Виктория Лебедева",
    initials: "ВЛ",
    question: "Я опять получила мало баллов. Кажется, у меня вообще не получится.",
    answer:
      "Виктория, один результат не определяет весь прогресс. В этой работе уже лучше получились задания первой части, а просели две конкретные темы. Давай на этой неделе спокойно разберем их и сравним следующий результат с сегодняшним.",
    subject: "Поддержка",
    receivedAt: "16:21",
    waitTime: "33 мин",
    status: "pending",
    confidence: 91,
    color: "#ffecec",
  },
  {
    id: "d4",
    studentId: "s3",
    student: "София Ким",
    initials: "СК",
    question: "А запись занятия сегодня будет?",
    answer:
      "София, да. Запись появится в личном кабинете в течение двух часов после окончания занятия.",
    subject: "Организация",
    receivedAt: "15:57",
    waitTime: "57 мин",
    status: "pending",
    confidence: 97,
    color: "#e7f7ee",
  },
];

const campaignsSeed: Campaign[] = [
  {
    id: "c1",
    title: "Напоминание о пробнике",
    date: "26 июля, 18:00",
    recipients: 42,
    sent: 42,
    failed: 0,
    status: "sent",
  },
  {
    id: "c2",
    title: "Материалы после занятия",
    date: "24 июля, 20:15",
    recipients: 28,
    sent: 27,
    failed: 1,
    status: "sent",
  },
  {
    id: "c3",
    title: "Мотивация перед неделей",
    date: "Черновик",
    recipients: 8,
    sent: 0,
    failed: 0,
    status: "draft",
  },
];

const navItems: Array<{
  id: Tab;
  label: string;
  icon: typeof Inbox;
}> = [
  { id: "answers", label: "Ответы", icon: MessageSquareText },
  { id: "students", label: "Ученики", icon: UsersRound },
  { id: "broadcasts", label: "Рассылки", icon: Megaphone },
];

function Avatar({
  initials,
  color,
  size = "medium",
}: {
  initials: string;
  color: string;
  size?: "small" | "medium" | "large";
}) {
  return (
    <span
      className={`avatar avatar-${size}`}
      style={{ backgroundColor: color }}
      aria-hidden="true"
    >
      {initials}
    </span>
  );
}

function StatusPill({
  tone,
  children,
}: {
  tone: "blue" | "green" | "amber" | "red" | "gray";
  children: React.ReactNode;
}) {
  return <span className={`status-pill status-${tone}`}>{children}</span>;
}

export default function Home() {
  const [activeTab, setActiveTab] = useState<Tab>("answers");
  const [drafts, setDrafts] = useState(draftsSeed);
  const [selectedDraftId, setSelectedDraftId] = useState(draftsSeed[0].id);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorText, setEditorText] = useState(draftsSeed[0].answer);
  const [savedText, setSavedText] = useState(draftsSeed[0].answer);
  const [isRegenerating, setIsRegenerating] = useState(false);
  const [studentQuery, setStudentQuery] = useState("");
  const [studentFilter, setStudentFilter] = useState<StudentFilter>("all");
  const [selectedStudents, setSelectedStudents] = useState<Set<string>>(
    new Set(["s1", "s2", "s3"]),
  );
  const [broadcastView, setBroadcastView] =
    useState<BroadcastView>("compose");
  const [brief, setBrief] = useState(
    "Напомни о пробном экзамене в субботу в 11:00 и попроси не опаздывать.",
  );
  const [tone, setTone] = useState<"friendly" | "neutral" | "strict">(
    "friendly",
  );
  const [broadcastText, setBroadcastText] = useState(
    "{first_name}, привет! Напоминаю: в эту субботу в 11:00 пройдет пробный экзамен. Приходи за 10 минут до начала, чтобы спокойно подготовиться. Удачи — у тебя все получится!",
  );
  const [previewStudentId, setPreviewStudentId] = useState("s1");
  const [isGenerating, setIsGenerating] = useState(false);
  const [sendConfirm, setSendConfirm] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [connection, setConnection] = useState<"demo" | "connecting" | "live">(
    "connecting",
  );
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const pendingDrafts = drafts.filter((draft) => draft.status === "pending");
  const selectedDraft =
    drafts.find((draft) => draft.id === selectedDraftId) ?? pendingDrafts[0];

  const filteredStudents = useMemo(() => {
    const query = studentQuery.trim().toLocaleLowerCase("ru");
    return studentsSeed.filter((student) => {
      const matchesQuery =
        !query ||
        `${student.name} ${student.group} ${student.course}`
          .toLocaleLowerCase("ru")
          .includes(query);
      const matchesFilter =
        studentFilter === "all" || student.status === studentFilter;
      return matchesQuery && matchesFilter;
    });
  }, [studentFilter, studentQuery]);

  const selectedStudentList = studentsSeed.filter((student) =>
    selectedStudents.has(student.id),
  );
  const previewStudent =
    studentsSeed.find((student) => student.id === previewStudentId) ??
    selectedStudentList[0] ??
    studentsSeed[0];
  const previewText = broadcastText
    .replaceAll("{first_name}", previewStudent.name.split(" ")[0])
    .replaceAll("{last_name}", previewStudent.name.split(" ")[1] ?? "")
    .replaceAll("{name}", previewStudent.name);

  useEffect(() => {
    const telegram = window.Telegram?.WebApp;
    telegram?.ready();
    telegram?.expand();

    const apiUrl = process.env.NEXT_PUBLIC_API_URL;
    if (!apiUrl || !telegram?.initData) {
      setConnection("demo");
      return;
    }

    fetch(`${apiUrl}/api/miniapp/session`, {
      headers: { "X-Telegram-Init-Data": telegram.initData },
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error("Mini App session rejected");
        }
        setConnection("live");
      })
      .catch(() => setConnection("demo"));
  }, []);

  useEffect(() => {
    if (selectedDraft) {
      setEditorText(selectedDraft.answer);
      setSavedText(selectedDraft.answer);
    }
  }, [selectedDraft?.id]);

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timeout = window.setTimeout(() => setToast(null), 2800);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  function haptic(type: "light" | "medium" | "heavy" = "light") {
    window.Telegram?.WebApp?.HapticFeedback?.impactOccurred(type);
  }

  function openDraft(draft: Draft) {
    setSelectedDraftId(draft.id);
    setEditorOpen(true);
    haptic();
  }

  function updateDraftStatus(status: DraftStatus) {
    if (!selectedDraft) {
      return;
    }
    setDrafts((current) =>
      current.map((draft) =>
        draft.id === selectedDraft.id
          ? { ...draft, answer: editorText.trim(), status }
          : draft,
      ),
    );
    const next = pendingDrafts.find((draft) => draft.id !== selectedDraft.id);
    if (next) {
      setSelectedDraftId(next.id);
      setEditorText(next.answer);
      setSavedText(next.answer);
    } else {
      setEditorOpen(false);
    }
    setToast(status === "sent" ? "Ответ отправлен ученику" : "Черновик отклонен");
    window.Telegram?.WebApp?.HapticFeedback?.notificationOccurred(
      status === "sent" ? "success" : "warning",
    );
  }

  function regenerateDraft() {
    if (!selectedDraft || isRegenerating) {
      return;
    }
    setIsRegenerating(true);
    window.setTimeout(() => {
      const firstName = selectedDraft.student.split(" ")[0];
      setEditorText(
        `${firstName}, давай разберем это спокойно. Здесь важно не запоминать правило механически, а увидеть само действие. ${selectedDraft.answer.split(". ").slice(1).join(". ")}`,
      );
      setIsRegenerating(false);
      setToast("AI подготовил новый вариант");
    }, 850);
  }

  function toggleStudent(id: string) {
    setSelectedStudents((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
    haptic();
  }

  function toggleVisibleStudents() {
    const allVisibleSelected = filteredStudents.every((student) =>
      selectedStudents.has(student.id),
    );
    setSelectedStudents((current) => {
      const next = new Set(current);
      filteredStudents.forEach((student) => {
        if (allVisibleSelected) {
          next.delete(student.id);
        } else {
          next.add(student.id);
        }
      });
      return next;
    });
  }

  function insertPlaceholder(value: string) {
    const textarea = textareaRef.current;
    const start = textarea?.selectionStart ?? broadcastText.length;
    const end = textarea?.selectionEnd ?? broadcastText.length;
    setBroadcastText(
      `${broadcastText.slice(0, start)}${value}${broadcastText.slice(end)}`,
    );
    requestAnimationFrame(() => {
      textarea?.focus();
      textarea?.setSelectionRange(start + value.length, start + value.length);
    });
  }

  function generateBroadcast() {
    if (isGenerating) {
      return;
    }
    setIsGenerating(true);
    window.setTimeout(() => {
      const opening =
        tone === "strict"
          ? "{first_name}, напоминаю:"
          : tone === "neutral"
            ? "{first_name}, добрый день!"
            : "{first_name}, привет!";
      setBroadcastText(
        `${opening} В эту субботу в 11:00 пройдет пробный экзамен. Пожалуйста, приходи за 10 минут до начала, чтобы успеть подготовиться. Возьми ручку, воду и хорошее настроение. До встречи!`,
      );
      setIsGenerating(false);
      setToast("Текст готов — можно редактировать");
    }, 950);
  }

  function sendBroadcast() {
    setSendConfirm(false);
    setToast(`Рассылка запущена: ${selectedStudents.size} получателей`);
    window.Telegram?.WebApp?.HapticFeedback?.notificationOccurred("success");
  }

  function changeTab(tab: Tab) {
    setActiveTab(tab);
    setEditorOpen(false);
    haptic();
  }

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Sparkles size={19} strokeWidth={2.2} />
          </div>
          <div>
            <strong>Curator AI</strong>
            <span>Рабочее пространство</span>
          </div>
        </div>

        <nav className="side-nav" aria-label="Основная навигация">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                className={activeTab === item.id ? "nav-item active" : "nav-item"}
                onClick={() => changeTab(item.id)}
              >
                <Icon size={19} />
                <span>{item.label}</span>
                {item.id === "answers" && pendingDrafts.length > 0 && (
                  <span className="nav-count">{pendingDrafts.length}</span>
                )}
              </button>
            );
          })}
        </nav>

        <div className="sidebar-foot">
          <div className="profile-row">
            <Avatar initials="НН" color="#e9f3ff" size="small" />
            <div>
              <strong>Нематулло</strong>
              <span>Математика</span>
            </div>
            <MoreHorizontal size={18} />
          </div>
        </div>
      </aside>

      <section className="main-area">
        <header className="topbar">
          <div className="mobile-brand">
            <span className="brand-mark">
              <Sparkles size={17} />
            </span>
            <strong>Curator AI</strong>
          </div>
          <div className="topbar-copy">
            <h1>
              {activeTab === "answers"
                ? "Ответы ученикам"
                : activeTab === "students"
                  ? "Ученики"
                  : "Рассылки"}
            </h1>
            <p>
              {activeTab === "answers"
                ? `${pendingDrafts.length} ждут проверки`
                : activeTab === "students"
                  ? `${studentsSeed.length} учеников в каталоге`
                  : `${campaignsSeed.length} кампании`}
            </p>
          </div>
          <div className="topbar-actions">
            <span className={`connection-dot connection-${connection}`} />
            <span className="connection-label">
              {connection === "live"
                ? "Подключено"
                : connection === "connecting"
                  ? "Подключение"
                  : "Демо-режим"}
            </span>
            <button className="icon-button" aria-label="Уведомления">
              <Inbox size={19} />
              <span className="notification-dot" />
            </button>
          </div>
        </header>

        {activeTab === "answers" && (
          <section
            className={`answers-layout ${editorOpen ? "mobile-editor-open" : ""}`}
          >
            <div className="queue-pane">
              <div className="pane-toolbar">
                <div className="segmented compact" aria-label="Фильтр ответов">
                  <button className="selected">Новые</button>
                  <button>Все</button>
                </div>
                <button className="icon-button" aria-label="Обновить">
                  <RefreshCw size={17} />
                </button>
              </div>

              <div className="draft-list">
                {pendingDrafts.length === 0 ? (
                  <div className="empty-state">
                    <span className="empty-icon">
                      <CheckCheck size={24} />
                    </span>
                    <strong>Все разобрано</strong>
                    <p>Новых ответов на проверку пока нет.</p>
                  </div>
                ) : (
                  pendingDrafts.map((draft) => (
                    <button
                      key={draft.id}
                      className={
                        selectedDraft?.id === draft.id
                          ? "draft-row active"
                          : "draft-row"
                      }
                      onClick={() => openDraft(draft)}
                    >
                      <Avatar
                        initials={draft.initials}
                        color={draft.color}
                        size="medium"
                      />
                      <span className="draft-copy">
                        <span className="draft-title-row">
                          <strong>{draft.student}</strong>
                          <time>{draft.receivedAt}</time>
                        </span>
                        <span className="draft-question">{draft.question}</span>
                        <span className="draft-meta">
                          <StatusPill
                            tone={
                              Number.parseInt(draft.waitTime) > 30
                                ? "amber"
                                : "blue"
                            }
                          >
                            <Clock3 size={12} />
                            {draft.waitTime}
                          </StatusPill>
                          <span>{draft.subject}</span>
                        </span>
                      </span>
                      <ChevronRight className="row-chevron" size={18} />
                    </button>
                  ))
                )}
              </div>
            </div>

            <div className="editor-pane">
              {selectedDraft ? (
                <>
                  <div className="editor-header">
                    <button
                      className="icon-button mobile-back"
                      onClick={() => setEditorOpen(false)}
                      aria-label="Назад к списку"
                    >
                      <ArrowLeft size={20} />
                    </button>
                    <Avatar
                      initials={selectedDraft.initials}
                      color={selectedDraft.color}
                      size="medium"
                    />
                    <div className="editor-person">
                      <strong>{selectedDraft.student}</strong>
                      <span>{selectedDraft.subject}</span>
                    </div>
                    <StatusPill tone="green">
                      <Sparkles size={12} />
                      AI {selectedDraft.confidence}%
                    </StatusPill>
                    <button className="icon-button" aria-label="Другие действия">
                      <MoreHorizontal size={19} />
                    </button>
                  </div>

                  <div className="editor-scroll">
                    <div className="question-block">
                      <div className="block-label">
                        <UserRound size={15} />
                        Вопрос ученика
                      </div>
                      <p>{selectedDraft.question}</p>
                    </div>

                    <div className="answer-block">
                      <div className="answer-label-row">
                        <div className="block-label">
                          <Bot size={16} />
                          Черновик AI
                        </div>
                        <button
                          className="text-button"
                          onClick={regenerateDraft}
                          disabled={isRegenerating}
                        >
                          {isRegenerating ? (
                            <LoaderCircle className="spin" size={15} />
                          ) : (
                            <RefreshCw size={15} />
                          )}
                          Еще вариант
                        </button>
                      </div>
                      <textarea
                        value={editorText}
                        onChange={(event) => setEditorText(event.target.value)}
                        aria-label="Ответ ученику"
                        rows={11}
                      />
                      <div className="textarea-foot">
                        <span>
                          {editorText.length} символов
                          {editorText !== savedText && " · есть изменения"}
                        </span>
                        {editorText !== savedText && (
                          <button
                            className="text-button quiet"
                            onClick={() => setEditorText(savedText)}
                          >
                            Отменить правки
                          </button>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="editor-actions">
                    <button
                      className="button secondary danger-button"
                      onClick={() => updateDraftStatus("rejected")}
                    >
                      <Trash2 size={17} />
                      Отклонить
                    </button>
                    <button
                      className="button primary"
                      disabled={!editorText.trim()}
                      onClick={() => updateDraftStatus("sent")}
                    >
                      <Send size={17} />
                      Отправить ответ
                    </button>
                  </div>
                </>
              ) : (
                <div className="empty-state editor-empty">
                  <span className="empty-icon">
                    <CheckCheck size={24} />
                  </span>
                  <strong>Очередь пуста</strong>
                  <p>Здесь появятся новые черновики AI.</p>
                </div>
              )}
            </div>
          </section>
        )}

        {activeTab === "students" && (
          <section className="content-page">
            <div className="students-toolbar">
              <label className="search-field">
                <Search size={18} />
                <input
                  type="search"
                  placeholder="Имя, группа или курс"
                  value={studentQuery}
                  onChange={(event) => setStudentQuery(event.target.value)}
                />
                {studentQuery && (
                  <button
                    onClick={() => setStudentQuery("")}
                    aria-label="Очистить поиск"
                  >
                    <X size={16} />
                  </button>
                )}
              </label>
              <div className="segmented" aria-label="Фильтр учеников">
                <button
                  className={studentFilter === "all" ? "selected" : ""}
                  onClick={() => setStudentFilter("all")}
                >
                  Все
                </button>
                <button
                  className={studentFilter === "active" ? "selected" : ""}
                  onClick={() => setStudentFilter("active")}
                >
                  Активные
                </button>
                <button
                  className={studentFilter === "attention" ? "selected" : ""}
                  onClick={() => setStudentFilter("attention")}
                >
                  Нужен контакт
                </button>
              </div>
              <button
                className="button primary students-compose"
                disabled={selectedStudents.size === 0}
                onClick={() => {
                  setActiveTab("broadcasts");
                  setBroadcastView("compose");
                }}
              >
                <Megaphone size={17} />
                Написать
                {selectedStudents.size > 0 && (
                  <span className="button-count">{selectedStudents.size}</span>
                )}
              </button>
            </div>

            <div className="selection-bar">
              <label className="check-control">
                <input
                  type="checkbox"
                  checked={
                    filteredStudents.length > 0 &&
                    filteredStudents.every((student) =>
                      selectedStudents.has(student.id),
                    )
                  }
                  onChange={toggleVisibleStudents}
                />
                <span />
              </label>
              <strong>Выбрано: {selectedStudents.size}</strong>
              <span>Можно выбрать до 100 учеников</span>
              {selectedStudents.size > 0 && (
                <button onClick={() => setSelectedStudents(new Set())}>
                  Снять выбор
                </button>
              )}
            </div>

            <div className="student-table">
              <div className="student-table-head">
                <span />
                <span>Ученик</span>
                <span>Группа</span>
                <span>Последняя активность</span>
                <span>Статус</span>
              </div>
              {filteredStudents.map((student) => (
                <label className="student-row" key={student.id}>
                  <span className="check-cell">
                    <span className="check-control">
                      <input
                        type="checkbox"
                        checked={selectedStudents.has(student.id)}
                        onChange={() => toggleStudent(student.id)}
                      />
                      <span />
                    </span>
                  </span>
                  <span className="student-identity">
                    <Avatar
                      initials={student.initials}
                      color={student.color}
                      size="medium"
                    />
                    <span>
                      <strong>{student.name}</strong>
                      <small>{student.course}</small>
                    </span>
                  </span>
                  <span className="student-group">{student.group}</span>
                  <span className="last-seen">{student.lastSeen}</span>
                  <span className="student-status">
                    <StatusPill
                      tone={student.status === "active" ? "green" : "amber"}
                    >
                      {student.status === "active"
                        ? "На связи"
                        : "Нужен контакт"}
                    </StatusPill>
                  </span>
                </label>
              ))}
              {filteredStudents.length === 0 && (
                <div className="empty-state table-empty">
                  <Search size={23} />
                  <strong>Никого не нашли</strong>
                  <p>Измени запрос или фильтр.</p>
                </div>
              )}
            </div>
          </section>
        )}

        {activeTab === "broadcasts" && (
          <section className="content-page broadcast-page">
            <div className="broadcast-tabs">
              <div className="segmented">
                <button
                  className={broadcastView === "compose" ? "selected" : ""}
                  onClick={() => setBroadcastView("compose")}
                >
                  <PencilLine size={15} />
                  Новая
                </button>
                <button
                  className={broadcastView === "history" ? "selected" : ""}
                  onClick={() => setBroadcastView("history")}
                >
                  <History size={15} />
                  История
                </button>
              </div>
            </div>

            {broadcastView === "compose" ? (
              <div className="composer-layout">
                <div className="composer-main">
                  <div className="section-heading">
                    <span className="step-index">1</span>
                    <div>
                      <h2>Получатели</h2>
                      <p>{selectedStudents.size} учеников выбрано</p>
                    </div>
                    <button
                      className="button secondary"
                      onClick={() => setActiveTab("students")}
                    >
                      <UsersRound size={16} />
                      Изменить
                    </button>
                  </div>

                  <div className="recipient-strip">
                    {selectedStudentList.slice(0, 6).map((student) => (
                      <button
                        className="recipient-chip"
                        key={student.id}
                        onClick={() => toggleStudent(student.id)}
                        title="Убрать из рассылки"
                      >
                        <Avatar
                          initials={student.initials}
                          color={student.color}
                          size="small"
                        />
                        <span>{student.name.split(" ")[0]}</span>
                        <X size={13} />
                      </button>
                    ))}
                    {selectedStudents.size > 6 && (
                      <span className="more-recipients">
                        +{selectedStudents.size - 6}
                      </span>
                    )}
                    {selectedStudents.size === 0 && (
                      <button
                        className="empty-recipient-button"
                        onClick={() => setActiveTab("students")}
                      >
                        <Plus size={16} />
                        Выбрать учеников
                      </button>
                    )}
                  </div>

                  <div className="section-divider" />

                  <div className="section-heading text-step">
                    <span className="step-index">2</span>
                    <div>
                      <h2>Сообщение</h2>
                      <p>AI подготовит основу, ты сможешь поправить текст</p>
                    </div>
                  </div>

                  <div className="ai-brief">
                    <label htmlFor="brief">Что нужно сказать</label>
                    <textarea
                      id="brief"
                      value={brief}
                      rows={3}
                      onChange={(event) => setBrief(event.target.value)}
                    />
                    <div className="ai-brief-foot">
                      <div className="tone-control">
                        <span>Тон</span>
                        <div className="segmented compact">
                          <button
                            className={tone === "friendly" ? "selected" : ""}
                            onClick={() => setTone("friendly")}
                          >
                            Дружелюбно
                          </button>
                          <button
                            className={tone === "neutral" ? "selected" : ""}
                            onClick={() => setTone("neutral")}
                          >
                            Нейтрально
                          </button>
                          <button
                            className={tone === "strict" ? "selected" : ""}
                            onClick={() => setTone("strict")}
                          >
                            Строго
                          </button>
                        </div>
                      </div>
                      <button
                        className="button ai-button"
                        disabled={!brief.trim() || isGenerating}
                        onClick={generateBroadcast}
                      >
                        {isGenerating ? (
                          <LoaderCircle className="spin" size={17} />
                        ) : (
                          <Sparkles size={17} />
                        )}
                        Сгенерировать
                      </button>
                    </div>
                  </div>

                  <div className="message-editor">
                    <div className="message-editor-head">
                      <label htmlFor="broadcast-message">Текст рассылки</label>
                      <span>{broadcastText.length} / 3500</span>
                    </div>
                    <textarea
                      ref={textareaRef}
                      id="broadcast-message"
                      rows={8}
                      value={broadcastText}
                      onChange={(event) => setBroadcastText(event.target.value)}
                    />
                    <div className="placeholder-row">
                      <span>Подставить:</span>
                      <button onClick={() => insertPlaceholder("{first_name}")}>
                        Имя
                      </button>
                      <button onClick={() => insertPlaceholder("{last_name}")}>
                        Фамилию
                      </button>
                      <button onClick={() => insertPlaceholder("{name}")}>
                        Полное имя
                      </button>
                    </div>
                  </div>
                </div>

                <aside className="preview-pane">
                  <div className="preview-head">
                    <div>
                      <span>Предпросмотр</span>
                      <strong>Сообщение в VK</strong>
                    </div>
                    <label className="preview-select">
                      <select
                        value={previewStudent.id}
                        onChange={(event) =>
                          setPreviewStudentId(event.target.value)
                        }
                      >
                        {(selectedStudentList.length
                          ? selectedStudentList
                          : studentsSeed
                        ).map((student) => (
                          <option value={student.id} key={student.id}>
                            {student.name}
                          </option>
                        ))}
                      </select>
                      <ChevronDown size={15} />
                    </label>
                  </div>
                  <div className="phone-preview">
                    <div className="vk-chat-head">
                      <Avatar
                        initials={previewStudent.initials}
                        color={previewStudent.color}
                        size="small"
                      />
                      <div>
                        <strong>{previewStudent.name}</strong>
                        <span>был(а) недавно</span>
                      </div>
                    </div>
                    <div className="vk-chat-body">
                      <div className="message-bubble">
                        <p>{previewText}</p>
                        <time>
                          16:54 <CheckCheck size={13} />
                        </time>
                      </div>
                    </div>
                  </div>
                  <button
                    className="button primary send-campaign"
                    disabled={
                      selectedStudents.size === 0 || !broadcastText.trim()
                    }
                    onClick={() => setSendConfirm(true)}
                  >
                    <Send size={17} />
                    Отправить {selectedStudents.size} ученикам
                  </button>
                </aside>
              </div>
            ) : (
              <div className="campaign-list">
                <div className="campaign-head">
                  <span>Рассылка</span>
                  <span>Получатели</span>
                  <span>Доставка</span>
                  <span>Статус</span>
                </div>
                {campaignsSeed.map((campaign) => (
                  <button className="campaign-row" key={campaign.id}>
                    <span className="campaign-title">
                      <span className="campaign-icon">
                        <Megaphone size={17} />
                      </span>
                      <span>
                        <strong>{campaign.title}</strong>
                        <small>{campaign.date}</small>
                      </span>
                    </span>
                    <span>{campaign.recipients}</span>
                    <span className="delivery-cell">
                      {campaign.status === "draft"
                        ? "—"
                        : `${campaign.sent} доставлено`}
                      {campaign.failed > 0 && (
                        <small>{campaign.failed} с ошибкой</small>
                      )}
                    </span>
                    <span>
                      <StatusPill
                        tone={
                          campaign.status === "sent"
                            ? campaign.failed
                              ? "amber"
                              : "green"
                            : campaign.status === "sending"
                              ? "blue"
                              : "gray"
                        }
                      >
                        {campaign.status === "sent"
                          ? campaign.failed
                            ? "Есть ошибки"
                            : "Завершена"
                          : campaign.status === "sending"
                            ? "Отправляется"
                            : "Черновик"}
                      </StatusPill>
                    </span>
                  </button>
                ))}
              </div>
            )}
          </section>
        )}
      </section>

      <nav className="bottom-nav" aria-label="Основная навигация">
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              className={activeTab === item.id ? "active" : ""}
              onClick={() => changeTab(item.id)}
            >
              <span className="bottom-icon">
                <Icon size={21} />
                {item.id === "answers" && pendingDrafts.length > 0 && (
                  <span className="bottom-count">{pendingDrafts.length}</span>
                )}
              </span>
              {item.label}
            </button>
          );
        })}
      </nav>

      {sendConfirm && (
        <div className="modal-backdrop" onMouseDown={() => setSendConfirm(false)}>
          <div
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="confirm-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <button
              className="icon-button dialog-close"
              aria-label="Закрыть"
              onClick={() => setSendConfirm(false)}
            >
              <X size={19} />
            </button>
            <span className="confirm-icon">
              <Megaphone size={23} />
            </span>
            <h2 id="confirm-title">Запустить рассылку?</h2>
            <p>
              Сообщение уйдет {selectedStudents.size} ученикам от имени
              подключенной VK-группы.
            </p>
            <div className="confirm-note">
              <CircleAlert size={17} />
              После запуска текст изменить нельзя.
            </div>
            <div className="dialog-actions">
              <button
                className="button secondary"
                onClick={() => setSendConfirm(false)}
              >
                Вернуться
              </button>
              <button className="button primary" onClick={sendBroadcast}>
                <Send size={17} />
                Запустить
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="toast" role="status">
          <Check size={17} />
          {toast}
        </div>
      )}
    </main>
  );
}
