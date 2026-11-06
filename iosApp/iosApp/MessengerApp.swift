import SwiftUI
import UIKit
import MessengerKit

struct MessengerView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosHostKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct MessengerApp: App {
    var body: some Scene {
        WindowGroup { MessengerView().ignoresSafeArea() }
    }
}
