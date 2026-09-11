import { create } from 'zustand';

type State = { theme: 'RED' | 'GREEN'; setTheme: (theme: 'RED' | 'GREEN') => void };

export const useStore = create<State>((set) => ({ theme: 'RED', setTheme: (theme) => set({ theme }) }));
