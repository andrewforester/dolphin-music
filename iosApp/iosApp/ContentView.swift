import ComposeApp
import SwiftUI

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea()
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
