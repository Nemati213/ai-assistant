import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Curator AI",
  description:
    "Рабочее пространство куратора для ответов ученикам и персональных рассылок.",
  icons: {
    icon: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ru">
      <head>
        <meta
          name="viewport"
          content="width=device-width, initial-scale=1, viewport-fit=cover"
        />
        <script src="https://telegram.org/js/telegram-web-app.js" />
      </head>
      <body>{children}</body>
    </html>
  );
}
