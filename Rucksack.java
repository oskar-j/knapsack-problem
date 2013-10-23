import java.text.*;
import java.util.*;

public class Rucksack {
	
	public static final int NORMAL = 0;
	public static final int ROULETTE = 1;
	public static final int TOURNAMENT = 2;
	// tryb selekcji najlepszych osobnikow w algorytmie genetycznym

	private static DecimalFormat waga = new DecimalFormat("0.00kg");
	private static boolean ruletka = false;
	
	// Maksymalny ciezar obiektu
	// maksymalna wartosc obiektu
	static final double MAX_CIEZAR = 30;
	static final int MAX_WARTOSC = 190;
	
	//ile obiektow wylosowac?
	static int liczebnoscObiektow = 100;
	
	static double[][] wyborObiektow = null; 
	// w tej tablicy elementy beda sobie siedziec, poki jakis lobuz ich nie wysprzata
	
	// pojemnosc plecaka
	static int pojemnosc;

	boolean[] wybor = null;
	
	//konstruktor
	Rucksack () {
		wybor = new boolean[liczebnoscObiektow];
		for (int i = 0; i < wybor.length; i ++)
			wybor[i] = false;
	}
	
	//konstruktor kopiujacy
	Rucksack (Rucksack r) {
		wybor = new boolean[liczebnoscObiektow];
		for (int i = 0; i < wybor.length; i ++)
			wybor[i] = r.wybor[i];
	}

	double ciezar () {
		double g = 0;
		for (int i=0; i < wybor.length; i ++)
			if (wybor[i] == true)
				g = g + wyborObiektow[i][0];
		return g;
	}

	int wartosc () {
		int n = 0;
		for (int i=0; i < wybor.length; i ++)
			if (wybor[i] == true)
				n = n + (int) wyborObiektow[i][1];
		return n;
	}

	
	public String toString() {
		String r = "|";
		for (int i=0; i < wybor.length; i++) 
			r = r + (wybor[i] ? "1" : "0") + "|";	
		r = r + " Ciezar: " + waga.format(ciezar())
			+ " Wartosc: " + wartosc();
		return r;
	}

	// statyczne metody

   	static double[][] wypelnijObiektami() {
		java.util.Random ra = new java.util.Random();
		double[][] r = new double[liczebnoscObiektow][2];
		for (int i=0; i < r.length; i++) {
			r[i][0]= (ra.nextDouble() * MAX_CIEZAR) + 0.5; // od 0,5 kg do 30 kg
			r[i][1]= ra.nextInt(MAX_WARTOSC) + 10; // od 10 zl do 200 zlotych
		}
	   	return r;
   	}

	static String obiektyToString(double[][] a) {
	   
		String r = "Wybor obiektow: ";
		for (int i=0; i < a.length; i++) 
			r = r + "(" + waga.format(a[i][0]) + "," + a[i][1] +")";
		return r;
	}

	// najlzejsze rzeczy 

	static Rucksack pakowaniePorownCiezar() {
		Rucksack r = new Rucksack();
		while (true) {
			int pos = -1;
			double najciezar = MAX_CIEZAR + 1;
			for(int i = 0 ; i < wyborObiektow.length; i ++)
				if (r.wybor[i] == false &&
						wyborObiektow[i][0] < najciezar &&
						r.ciezar() + wyborObiektow[i][0] <= pojemnosc) {
					najciezar = wyborObiektow[i][0];
					pos = i;
				}
			if (pos == -1) break;
			else r.wybor[pos] = true;
		}
		return r;
	}

	// najbardziej wartosciowe

	static Rucksack pakowaniePorownWartosc() {
		Rucksack r = new Rucksack();
		while (true) {
			int pos = -1;
			int najwartosc = 0;
			for(int i = 0; i < wyborObiektow.length; i ++)
				if (r.wybor[i] == false &&
						wyborObiektow[i][1] > najwartosc &&
						r.ciezar() + wyborObiektow[i][0] <= pojemnosc) {
					najwartosc = (int) wyborObiektow[i][1];
					pos = i;
				}
			if (pos == -1) break;
			else r.wybor[pos] = true;
		}
		return r;
	}

	// rozwiazanie problemu za pomoca backtrackingu

	static Rucksack pakowanieBacktracking() {
		return RucksackRekursja(0, new Rucksack());
	}

	static Rucksack RucksackRekursja(int i, Rucksack r) {
		if (i == wyborObiektow.length) return r;

		Rucksack r1 = new Rucksack(r);
		r1 = RucksackRekursja(i+1, r1);

		if (r.ciezar() + wyborObiektow[i][0] <= pojemnosc) {
			Rucksack r2 = new Rucksack(r);
			r2.wybor[i] = true;
			r2 = RucksackRekursja(i+1,r2);

			if (r2.wartosc() > r1.wartosc()) return r2;
		}

		return r1;
	}

	
	static Rucksack pakowanieDynamiczneProgramowanie() {

		Rucksack[][] rezultaty = new Rucksack[pojemnosc+1][liczebnoscObiektow];
		for (int i=0; i <= pojemnosc; i++)
			for (int j=0; j < liczebnoscObiektow; j++)
				rezultaty[i][j] = null;
		return RucksackRekursjaDP(0, new Rucksack(), rezultaty);
	}

	static Rucksack RucksackRekursjaDP(int i, Rucksack r, Rucksack[][] rezultaty) {
		if (i == wyborObiektow.length) return r;
		double ciezar = r.ciezar();

		if (rezultaty[(int)ciezar][i] != null) {
			for (int j = i; j < liczebnoscObiektow; j++)
				r.wybor[j] = rezultaty[(int)ciezar][i].wybor[j];
			return r;
		}

		Rucksack r1 = new Rucksack(r);
		r1 = RucksackRekursjaDP(i+1, r1, rezultaty);
		if (ciezar + wyborObiektow[i][0] <= pojemnosc) {
			Rucksack r2 = new Rucksack(r);
			r2.wybor[i] = true;
			r2 = RucksackRekursjaDP(i+1,r2,rezultaty);
			if (r2.wartosc() > r1.wartosc()) r1  = r2;
		}

		rezultaty[(int)ciezar][i] = r1;
		return r1;
	}

	// algorytmy genetyczne

	Rucksack mutate() {
		// mutacja
		java.util.Random ra = new java.util.Random();
		int pos = ra.nextInt(wybor.length);
		Rucksack r = new Rucksack(this);
		r.wybor[pos] = (wybor[pos] ? false : true);
		return r;
	}

	Rucksack crossover(Rucksack partner) {
		// krzyzowanie dwoch plecakow
		java.util.Random ra = new java.util.Random();
		int pos = ra.nextInt(wybor.length);
		Rucksack r = new Rucksack();
		for (int i=0; i < pos; i++)
			r.wybor[i] = wybor[i];
		for (int i=pos; i < wybor.length; i++)
			r.wybor[i] = partner.wybor[i];
		return r;
	}

	int fitness() {
		if (ciezar() > pojemnosc) return 0;
		else return wartosc();
	}

	static Rucksack pakowanieGenetyka(int selection_mode) {
		
		int POOL_SIZE = 100; // liczba osobnikow, tudziez plecakow, w populacji
		int BEST_SIZE = 20; // liczba najlepszych osobnikow (przetrwaja najsilniejsi)
		int NUM_GENERATIONS = liczebnoscObiektow * 20; // liczba generacji tych 'zyjatek'
		//liczbe ta trzeba zmniejszyc dla wielu danych bo bedziemy czekac wiecznosc
		//na wyniki
		
		int generation = 0;
		
		// Inicjalizacja poczatkowej generacji
		Rucksack[] pool = new Rucksack[POOL_SIZE];
		// tablica plecakow - na niej bedziemy sie opierac w dalszym kodzie
		
		// Mozliwosc 1: zaczynami z pustymi plecakami
		pool[0] = new Rucksack(); 
		pool[1] = new Rucksack();
		
		// Mozliwosc 2:
		//pool[0] = pakowaniePorownWartosc();  
		//pool[1] = pakowaniePorownCiezar();
		
		for (int i = 2; i < BEST_SIZE; i++) 
			pool[i] = pool[i % 2].mutate();
		// Testowanie rozmaitych generacji
		while(generation < NUM_GENERATIONS) {
			// Krok 1: Mutacja i krzyzowanie
			for (int i = BEST_SIZE; i < POOL_SIZE; i++) {
				java.util.Random ra = new java.util.Random();
				// mutowanie z prawdopodobieñstwem 0,7
				if (ra.nextFloat() < 0.7) pool[i] = pool[i % BEST_SIZE].mutate();
				// prawdopodobienstwo 0,3
				// krzyzowanie
				else pool[i] = pool[i % BEST_SIZE].crossover(pool[ra.nextInt(BEST_SIZE)]);
			}
			// Krok 2: wybor najlepszego kandydata
			
			//z ruletka albo bez
			
			if(selection_mode == 1){ // ruletka
				// najwieksze szanse maja plecaki o najwiekszej wartosci
				// chociaz szanse wygrania maja teorytycznie wszystkie, ktore nie sa za ciezkie
				int totalFitness = -1;
				int roulette[][] = new int[POOL_SIZE][2];
				for (int i = 0 ; i < POOL_SIZE ; i++){
					roulette[i][0] = totalFitness + 1;
					totalFitness += pool[i].fitness();
					roulette[i][1] = totalFitness;
				}
				java.util.Random ra = new java.util.Random();
				for(int i = 0 ; i < BEST_SIZE ; i++){
					int rand = ra.nextInt(totalFitness);
					for(int j = 0 ; j < roulette.length ; j++){
						if(roulette[j][0] <= rand && rand < roulette[j][1]) { pool[i]=pool[j]; break; }
					}
				}
			} else
			if(selection_mode == 2){ // turniej
				// osobniki dzielimy na podgrupy
				// wybor deterministyczny - z grupy wychodza najlepsze osobniki
				ArrayList[] groups = new ArrayList[BEST_SIZE];
				for(int i = 0 ; i < BEST_SIZE ; i++) groups[i] = 
					new ArrayList((int)(POOL_SIZE / BEST_SIZE) + 1);
				int c = 0;
				for(int i = 0 ; i < POOL_SIZE ; i++){
					int[][] fitness = { {i,} , {pool[i].fitness(),} };
					groups[c].add(fitness);
					c = c < 39 ? c++ : 0;
				}
				for(int i = 0 ; i < BEST_SIZE ; i++){
					int najlepszyZGrupy = 0;
					int najlepszyFitness = 0;
					Iterator it = groups[i].iterator ();
					while (it.hasNext ()) {
						int[][] a = (int[][]) it.next();
						if(a[1][0] > najlepszyFitness ) {najlepszyZGrupy = a[0][0] ; najlepszyFitness = a[1][0];}
					}
					pool[i] = pool[najlepszyZGrupy];
				}
				
			}else
			{
			for (int i = BEST_SIZE ; i < POOL_SIZE ; i ++) {
			    int worstFitness = Integer.MAX_VALUE;
				int pos = -1;
				for (int j = 0; j < BEST_SIZE; j ++)
					if (pool[i].fitness() > pool[j].fitness() 
							&& pool[j].fitness() < worstFitness) {
						worstFitness = pool[j].fitness();
						pos = j;
					}
				if (pos >= 0) pool[pos] = pool[i];
			}}
			
			generation++; // next generation, please !
			
		}
		// Na koniec najlepsze znalezione rozwiazanie
		int pos = -1;
		int bestFitness = 0;
		for (int i = 0; i < POOL_SIZE; i ++) 
			if (pool[i].fitness() > bestFitness) {
				bestFitness = pool[i].fitness();
				pos = i;
			};
		return pool[pos];
	}
	
	// To-do list, czyli main string args..
	
	public static void main (String args[]) {
		if (args.length == 1)
			liczebnoscObiektow = Integer.parseInt(args[0]);
		pojemnosc = (int)(liczebnoscObiektow * MAX_CIEZAR / 4);
		// ulatwienie dla nas, jezeli obiektow jest 100, a maksymalny ciezar obiektu to
		// 30 kg, pojemnosc plecaczka bedzie rowna 750 kg ;O

		wyborObiektow = wypelnijObiektami();
		System.out.println(obiektyToString(wyborObiektow));
		System.out.println("Pojemnosc: " + pojemnosc);
		System.out.println("");

		//Rucksack r1 = pakowaniePorownCiezar();
		//System.out.println("Metoda zachlanna, najlzejsze rzeczy: " + r1 );
		//System.out.println("");
		//taka ciekawostka, troche odbiega od ksiazkowego opisu problemu plecakowego
		//metoda zaimplementowana na wypadek, gdyby zlodzieja bolaly plecy

		Rucksack r2 = pakowaniePorownWartosc();
		System.out.println("Metoda zachlanna, najbardziej wartosciowe rzeczy: " + r2 );
		//metoda zachlanna, dziala prawidlowo przy praktycznie kazdych danych

		//Rucksack r3 = pakowanieBacktracking();
		//System.out.println("Backtracking: " + r3 );
		//System.out.println("");
		//Przy bardzo wielu elementach, tudzie¿ 30 i wiêcej, lepiej wylaczyc
		//backtracking, chyba ze wlasnie wynajelismy BlueGene/L , wtedy mozna zaszalec ;)

		//Rucksack r4 = pakowanieDynamiczneProgramowanie();
		//System.out.println("Programowanie dynamiczne: " + r4 );
		//Algorytm pseudowielomianowy, daje wynik w przyzwoitym czasie procesora

		System.out.println("\nTo moze chwile zajac.. max 20sekund na 2.6GHz");
		Rucksack r5 = pakowanieGenetyka(Rucksack.NORMAL);
		System.out.println("Algorytm genetyczny, selekcja liniowa: " + r5 );
		System.out.println("");
		
		System.out.println("To moze chwile zajac.. max 20sekund na 2.6GHz");
		Rucksack r6 = pakowanieGenetyka(Rucksack.ROULETTE);
		System.out.println("Algorytm genetyczny, selekcja z ruletka: " + r6 );
		System.out.println("");
		
		System.out.println("To moze chwile zajac.. max 20sekund na 2.6GHz");
		Rucksack r7 = pakowanieGenetyka(Rucksack.TOURNAMENT);
		System.out.println("Algorytm genetyczny, selekcja turniejowa: " + r7 );
		System.out.println("");
	}
}
