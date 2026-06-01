import App from './App';
import { ThemeProvider } from './ThemeProvider';

export default function Root() {
  return (
    <ThemeProvider>
      <App />
    </ThemeProvider>
  );
}
